package com.ledgerflow.payments.internal.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class PaymentProviderCircuitHealthIndicator implements HealthIndicator {

  private final CircuitBreaker circuitBreaker;

  PaymentProviderCircuitHealthIndicator(CircuitBreaker circuitBreaker) {
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  public Health health() {
    CircuitBreaker.State state = circuitBreaker.getState();
    Health.Builder health = state == CircuitBreaker.State.OPEN ? Health.down() : Health.up();
    return health
        .withDetail("state", state.name())
        .withDetail("failureRate", circuitBreaker.getMetrics().getFailureRate())
        .withDetail("bufferedCalls", circuitBreaker.getMetrics().getNumberOfBufferedCalls())
        .build();
  }
}
