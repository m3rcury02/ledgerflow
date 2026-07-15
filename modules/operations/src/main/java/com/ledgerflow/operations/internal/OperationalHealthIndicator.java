package com.ledgerflow.operations.internal;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;

final class OperationalHealthIndicator implements HealthIndicator {

  private final ReadinessProbeCache readinessProbeCache;
  private final DrainableWorkTracker workTracker;
  private final Environment environment;

  OperationalHealthIndicator(
      ReadinessProbeCache readinessProbeCache,
      DrainableWorkTracker workTracker,
      Environment environment) {
    this.readinessProbeCache = readinessProbeCache;
    this.workTracker = workTracker;
    this.environment = environment;
  }

  @Override
  public Health health() {
    if (!readinessProbeCache.readiness(kafkaRequired()).available()) {
      return Health.down().build();
    }
    if (!workTracker.isAcceptingWork() || workTracker.drainTimedOut()) {
      return Health.down().build();
    }
    return Health.up().build();
  }

  private boolean kafkaRequired() {
    return environment.getProperty("ledgerflow.messaging.publisher-enabled", Boolean.class, false)
        || environment.getProperty("ledgerflow.notifications.enabled", Boolean.class, false);
  }
}
