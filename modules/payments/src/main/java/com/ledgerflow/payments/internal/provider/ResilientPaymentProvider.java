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
import java.util.UUID;
import java.util.function.Supplier;

final class ResilientPaymentProvider implements PaymentProvider {

  private final PaymentProvider delegate;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;
  private final WorkTracker workTracker;
  private final FaultInjection faultInjection;

  ResilientPaymentProvider(
      PaymentProvider delegate,
      CircuitBreaker circuitBreaker,
      Bulkhead bulkhead,
      WorkTracker workTracker,
      FaultInjection faultInjection) {
    this.delegate = delegate;
    this.circuitBreaker = circuitBreaker;
    this.bulkhead = bulkhead;
    this.workTracker = workTracker;
    this.faultInjection = faultInjection;
  }

  @Override
  public ProviderResult authorize(AuthorizationRequest request) {
    return call(() -> delegate.authorize(request));
  }

  @Override
  public ProviderResult capture(CaptureRequest request) {
    return call(() -> delegate.capture(request));
  }

  @Override
  public ProviderLookupResult lookup(
      PaymentStage stage, UUID providerRequestId, String correlationId) {
    WorkToken work = workTracker.begin("payment-provider-lookup");
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
      return guarded.get();
    } catch (BulkheadFullException | CallNotPermittedException exception) {
      return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_RESILIENCE_LIMIT");
    } catch (InjectedFaultException exception) {
      return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_FAULT_INJECTED");
    } finally {
      work.close();
    }
  }

  CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  private ProviderResult call(Supplier<ProviderResult> operation) {
    WorkToken work = workTracker.begin("payment-provider-call");
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
      return guarded.get();
    } catch (BulkheadFullException exception) {
      return new ProviderResult.TemporaryFailure("PROVIDER_BULKHEAD_FULL");
    } catch (CallNotPermittedException exception) {
      return new ProviderResult.TemporaryFailure("PROVIDER_CIRCUIT_OPEN");
    } catch (InjectedFaultException exception) {
      return new ProviderResult.TemporaryFailure("PROVIDER_FAULT_INJECTED");
    } finally {
      work.close();
    }
  }
}
