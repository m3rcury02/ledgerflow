package com.ledgerflow.payments.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.ProviderLookupResult;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilientPaymentProviderTest {

  @Test
  void confirmedDeclinesDoNotCountAsProviderAvailabilityFailures() {
    AtomicInteger calls = new AtomicInteger();
    PaymentProvider delegate =
        provider(
            () -> {
              calls.incrementAndGet();
              return new ProviderResult.Declined("CONFIRMED_DECLINE");
            });
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "decline-test-provider",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100.0f)
                .recordResult(result -> result instanceof ProviderResult.TemporaryFailure)
                .build());
    ResilientPaymentProvider provider = resilient(delegate, circuitBreaker, 1);

    assertThat(provider.authorize(request())).isInstanceOf(ProviderResult.Declined.class);
    assertThat(provider.authorize(request())).isInstanceOf(ProviderResult.Declined.class);

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(calls).hasValue(2);
    assertThat(
            PaymentProviderConfiguration.providerAvailabilityFailure(
                new ProviderResult.Declined("CONFIRMED_DECLINE")))
        .isFalse();
  }

  @Test
  void circuitOpensAfterBoundedFailuresAndRecoversThroughHalfOpen() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    PaymentProvider delegate =
        provider(
            () ->
                calls.incrementAndGet() <= 2
                    ? new ProviderResult.TemporaryFailure("TEMPORARY")
                    : new ProviderResult.Success("provider-ok"));
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "test-provider",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(Duration.ofMillis(25))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordResult(result -> result instanceof ProviderResult.TemporaryFailure)
                .build());
    ResilientPaymentProvider provider = resilient(delegate, circuitBreaker, 1);

    assertThat(provider.authorize(request())).isInstanceOf(ProviderResult.TemporaryFailure.class);
    assertThat(provider.authorize(request())).isInstanceOf(ProviderResult.TemporaryFailure.class);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(((ProviderResult.TemporaryFailure) provider.authorize(request())).failureCode())
        .isEqualTo("PROVIDER_CIRCUIT_OPEN");
    assertThat(calls).hasValue(2);

    Thread.sleep(Duration.ofMillis(40));

    assertThat(provider.authorize(request())).isInstanceOf(ProviderResult.Success.class);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(calls).hasValue(3);
  }

  @Test
  void bulkheadRejectsConcurrentWorkWithoutStartingAnotherProviderCall() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    PaymentProvider delegate =
        provider(
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              try {
                release.await(1, TimeUnit.SECONDS);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return new ProviderResult.Success("provider-ok");
            });
    CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("bulkhead-test-provider");
    ResilientPaymentProvider provider = resilient(delegate, circuitBreaker, 1);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> provider.authorize(request()));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

      ProviderResult second = provider.authorize(request());

      assertThat(second).isEqualTo(new ProviderResult.TemporaryFailure("PROVIDER_BULKHEAD_FULL"));
      assertThat(calls).hasValue(1);
      release.countDown();
      assertThat(first.get(1, TimeUnit.SECONDS)).isInstanceOf(ProviderResult.Success.class);
    }
  }

  private ResilientPaymentProvider resilient(
      PaymentProvider delegate, CircuitBreaker circuitBreaker, int concurrentCalls) {
    Bulkhead bulkhead =
        Bulkhead.of(
            "test-bulkhead",
            BulkheadConfig.custom()
                .maxConcurrentCalls(concurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build());
    WorkTracker tracker =
        new WorkTracker() {
          @Override
          public WorkToken begin(String operation) {
            return () -> java.util.Objects.requireNonNull(operation);
          }

          @Override
          public boolean isAcceptingWork() {
            return true;
          }
        };
    return new ResilientPaymentProvider(
        delegate,
        circuitBreaker,
        bulkhead,
        tracker,
        point -> java.util.Objects.requireNonNull(point),
        new PaymentProviderMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            circuitBreaker,
            bulkhead));
  }

  private PaymentProvider provider(java.util.function.Supplier<ProviderResult> result) {
    return new PaymentProvider() {
      @Override
      public ProviderResult authorize(AuthorizationRequest request) {
        return result.get();
      }

      @Override
      public ProviderResult capture(CaptureRequest request) {
        return result.get();
      }

      @Override
      public ProviderLookupResult lookup(
          PaymentStage stage, UUID providerRequestId, String correlationId) {
        return new ProviderLookupResult.NotFound();
      }
    };
  }

  private PaymentProvider.AuthorizationRequest request() {
    return new PaymentProvider.AuthorizationRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PaymentMoney(100, "INR"),
        "pm_mock_success",
        "resilience-test");
  }
}
