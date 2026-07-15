package com.ledgerflow.payments.internal.provider;

import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.InjectedFaultException;
import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.ProviderLookupResult;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

final class ResilientPaymentProvider implements PaymentProvider {

  private final PaymentProvider delegate;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;
  private final WorkTracker workTracker;
  private final FaultInjection faultInjection;
  private final PaymentProviderMetrics metrics;

  ResilientPaymentProvider(
      PaymentProvider delegate,
      CircuitBreaker circuitBreaker,
      Bulkhead bulkhead,
      WorkTracker workTracker,
      FaultInjection faultInjection,
      PaymentProviderMetrics metrics) {
    this.delegate = delegate;
    this.circuitBreaker = circuitBreaker;
    this.bulkhead = bulkhead;
    this.workTracker = workTracker;
    this.faultInjection = faultInjection;
    this.metrics = metrics;
  }

  @Override
  public ProviderResult authorize(AuthorizationRequest request) {
    return call(PaymentStage.AUTHORIZATION, () -> delegate.authorize(request));
  }

  @Override
  public ProviderResult capture(CaptureRequest request) {
    return call(PaymentStage.CAPTURE, () -> delegate.capture(request));
  }

  @Override
  public ProviderLookupResult lookup(
      PaymentStage stage, UUID providerRequestId, String correlationId) {
    WorkToken work = workTracker.begin("payment-provider-lookup");
    long startedAt = System.nanoTime();
    ProviderLookupResult result;
    try {
      Supplier<ProviderLookupResult> guarded =
          Bulkhead.decorateSupplier(
              bulkhead,
              CircuitBreaker.decorateSupplier(
                  circuitBreaker,
                  () -> {
                    faultInjection.before(FaultPoint.PAYMENT_PROVIDER);
                    return delegate.lookup(stage, providerRequestId, correlationId);
                  }));
      result = guarded.get();
    } catch (BulkheadFullException exception) {
      metrics.bulkheadRejected();
      result = new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_RESILIENCE_LIMIT");
    } catch (CallNotPermittedException exception) {
      metrics.circuitRejected();
      result = new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_RESILIENCE_LIMIT");
    } catch (InjectedFaultException exception) {
      result = new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_FAULT_INJECTED");
    } finally {
      work.close();
    }
    metrics.record(stage, "lookup", result, Duration.ofNanos(System.nanoTime() - startedAt));
    return result;
  }

  CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  private ProviderResult call(PaymentStage stage, Supplier<ProviderResult> operation) {
    WorkToken work = workTracker.begin("payment-provider-call");
    long startedAt = System.nanoTime();
    ProviderResult result;
    try {
      Supplier<ProviderResult> guarded =
          Bulkhead.decorateSupplier(
              bulkhead,
              CircuitBreaker.decorateSupplier(
                  circuitBreaker,
                  () -> {
                    faultInjection.before(FaultPoint.PAYMENT_PROVIDER);
                    return operation.get();
                  }));
      result = guarded.get();
    } catch (BulkheadFullException exception) {
      metrics.bulkheadRejected();
      result = new ProviderResult.TemporaryFailure("PROVIDER_BULKHEAD_FULL");
    } catch (CallNotPermittedException exception) {
      metrics.circuitRejected();
      result = new ProviderResult.TemporaryFailure("PROVIDER_CIRCUIT_OPEN");
    } catch (InjectedFaultException exception) {
      result = new ProviderResult.TemporaryFailure("PROVIDER_FAULT_INJECTED");
    } finally {
      work.close();
    }
    metrics.record(stage, "call", result, Duration.ofNanos(System.nanoTime() - startedAt));
    return result;
  }
}
