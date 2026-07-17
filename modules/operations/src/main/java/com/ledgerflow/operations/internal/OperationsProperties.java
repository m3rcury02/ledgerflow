package com.ledgerflow.operations.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerflow.operations")
public record OperationsProperties(
    @DefaultValue("20s") Duration drainTimeout,
    @DefaultValue("3s") Duration dependencyTimeout,
    @DefaultValue("2s") Duration healthProbeCacheTtl,
    @DefaultValue("true") boolean startupValidationEnabled,
    @DefaultValue("10") int recoveryBatchSize,
    @DefaultValue("1s") Duration recoveryPollInterval,
    @DefaultValue("30s") Duration recoveryLeaseDuration,
    @DefaultValue("2s") Duration recoveryRecheckDelay,
    @DefaultValue("5m") Duration retryCooldown,
    @DefaultValue("3") int maxAutomaticAttempts,
    @DefaultValue("2") int maxBreakGlassAttempts,
    @DefaultValue("true") boolean recoveryWorkerEnabled) {

  public OperationsProperties {
    requirePositive(drainTimeout, "drainTimeout");
    requirePositive(dependencyTimeout, "dependencyTimeout");
    requirePositive(healthProbeCacheTtl, "healthProbeCacheTtl");
    requirePositive(recoveryPollInterval, "recoveryPollInterval");
    requirePositive(recoveryLeaseDuration, "recoveryLeaseDuration");
    requirePositive(recoveryRecheckDelay, "recoveryRecheckDelay");
    requirePositive(retryCooldown, "retryCooldown");
    if (healthProbeCacheTtl.compareTo(Duration.ofMillis(250)) < 0
        || healthProbeCacheTtl.compareTo(Duration.ofSeconds(10)) > 0) {
      throw new IllegalArgumentException("healthProbeCacheTtl must be between 250ms and 10s");
    }
    if (recoveryBatchSize < 1 || recoveryBatchSize > 100) {
      throw new IllegalArgumentException("recoveryBatchSize must be between 1 and 100");
    }
    if (recoveryLeaseDuration.compareTo(Duration.ofSeconds(5)) < 0
        || recoveryLeaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("recoveryLeaseDuration must be between 5s and 10m");
    }
    if (recoveryRecheckDelay.compareTo(recoveryLeaseDuration) >= 0) {
      throw new IllegalArgumentException("recoveryRecheckDelay must be shorter than the lease");
    }
    if (retryCooldown.compareTo(Duration.ofSeconds(1)) < 0
        || retryCooldown.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException("retryCooldown must be between 1s and 1d");
    }
    if (maxAutomaticAttempts < 1 || maxAutomaticAttempts > 10) {
      throw new IllegalArgumentException("maxAutomaticAttempts must be between 1 and 10");
    }
    if (maxBreakGlassAttempts < 1 || maxBreakGlassAttempts > 5) {
      throw new IllegalArgumentException("maxBreakGlassAttempts must be between 1 and 5");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
