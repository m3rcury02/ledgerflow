package com.ledgerflow.payments.internal.provider;

import com.ledgerflow.payments.internal.application.ProviderLookupResult;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

final class PaymentProviderMetrics {

  private final MeterRegistry meterRegistry;
  private final Counter timeouts;
  private final Counter bulkheadRejections;
  private final Counter circuitRejections;

  PaymentProviderMetrics(
      MeterRegistry meterRegistry, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
    this.meterRegistry = meterRegistry;
    timeouts =
        Counter.builder("ledgerflow.payment.provider.timeouts")
            .description("Provider calls whose outcome became unknown after a timeout")
            .register(meterRegistry);
    bulkheadRejections =
        Counter.builder("ledgerflow.payment.provider.bulkhead.rejections")
            .description("Provider calls rejected by the concurrency bulkhead")
            .register(meterRegistry);
    circuitRejections =
        Counter.builder("ledgerflow.payment.provider.circuit.rejections")
            .description("Provider calls rejected by the open circuit")
            .register(meterRegistry);

    for (CircuitState state : CircuitState.values()) {
      Gauge.builder(
              "ledgerflow.payment.provider.circuit.state",
              circuitBreaker,
              breaker -> state.matches(breaker.getState()) ? 1.0 : 0.0)
          .description("Current provider circuit state as one-hot gauges")
          .tag("state", state.tag)
          .register(meterRegistry);
    }
    Gauge.builder(
            "ledgerflow.payment.provider.bulkhead.available",
            bulkhead,
            value -> value.getMetrics().getAvailableConcurrentCalls())
        .description("Available provider bulkhead permits")
        .register(meterRegistry);
    Gauge.builder(
            "ledgerflow.payment.provider.bulkhead.maximum",
            bulkhead,
            value -> value.getMetrics().getMaxAllowedConcurrentCalls())
        .description("Configured provider bulkhead permits")
        .register(meterRegistry);
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                Counter.builder("ledgerflow.payment.provider.circuit.transitions")
                    .description("Provider circuit state transitions")
                    .tag("state", CircuitState.from(event.getStateTransition().getToState()).tag)
                    .register(meterRegistry)
                    .increment());
  }

  void record(PaymentStage stage, String activity, Object result, Duration duration) {
    String outcome = outcome(result);
    String stageTag = stage.name().toLowerCase(Locale.ROOT);
    Counter.builder("ledgerflow.payment.provider.attempts")
        .description("Provider attempts by stage, activity, and bounded outcome")
        .tag("stage", stageTag)
        .tag("activity", activity)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
    Timer.builder("ledgerflow.payment.provider.duration")
        .description("Provider call duration by stage, activity, and bounded outcome")
        .tag("stage", stageTag)
        .tag("activity", activity)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(duration);
    if (result instanceof ProviderResult.Unknown unknown
        && "PROVIDER_TIMEOUT".equals(unknown.failureCode())) {
      timeouts.increment();
    }
  }

  void bulkheadRejected() {
    bulkheadRejections.increment();
  }

  void circuitRejected() {
    circuitRejections.increment();
  }

  private String outcome(Object result) {
    if (result instanceof ProviderResult.Success
        || result instanceof ProviderLookupResult.FoundSuccess) {
      return "success";
    }
    if (result instanceof ProviderResult.Declined
        || result instanceof ProviderLookupResult.FoundDecline) {
      return "decline";
    }
    if (result instanceof ProviderResult.TemporaryFailure
        || result instanceof ProviderLookupResult.TemporarilyUnavailable) {
      return "temporary";
    }
    if (result instanceof ProviderResult.Unknown) {
      return "unknown";
    }
    if (result instanceof ProviderLookupResult.NotFound) {
      return "not_found";
    }
    return "invalid";
  }

  private enum CircuitState {
    CLOSED("closed"),
    OPEN("open"),
    HALF_OPEN("half_open");

    private final String tag;

    CircuitState(String tag) {
      this.tag = tag;
    }

    private boolean matches(CircuitBreaker.State state) {
      return this == from(state);
    }

    private static CircuitState from(CircuitBreaker.State state) {
      return switch (state) {
        case OPEN, FORCED_OPEN -> OPEN;
        case HALF_OPEN -> HALF_OPEN;
        case CLOSED, DISABLED, METRICS_ONLY -> CLOSED;
      };
    }
  }
}
