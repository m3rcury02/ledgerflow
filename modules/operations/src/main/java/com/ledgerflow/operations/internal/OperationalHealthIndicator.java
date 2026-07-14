package com.ledgerflow.operations.internal;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;

final class OperationalHealthIndicator implements HealthIndicator {

  private final DependencyProbe probe;
  private final DrainableWorkTracker workTracker;
  private final Environment environment;

  OperationalHealthIndicator(
      DependencyProbe probe, DrainableWorkTracker workTracker, Environment environment) {
    this.probe = probe;
    this.workTracker = workTracker;
    this.environment = environment;
  }

  @Override
  public Health health() {
    Health.Builder health = Health.up();
    try {
      probe.database();
      health.withDetail("database", "available");
      if (kafkaRequired()) {
        health.withDetail("kafkaClusterId", probe.kafka(java.util.Set.of()));
      }
      if (!workTracker.isAcceptingWork() || workTracker.drainTimedOut()) {
        return health
            .down()
            .withDetail("acceptingWork", workTracker.isAcceptingWork())
            .withDetail("drainTimedOut", workTracker.drainTimedOut())
            .withDetail("activeWork", workTracker.activeWork())
            .build();
      }
      return health.withDetail("acceptingWork", true).build();
    } catch (RuntimeException exception) {
      return health.down().withDetail("failure", "dependency unavailable").build();
    }
  }

  private boolean kafkaRequired() {
    return environment.getProperty("ledgerflow.messaging.publisher-enabled", Boolean.class, false)
        || environment.getProperty("ledgerflow.notifications.enabled", Boolean.class, false);
  }
}
