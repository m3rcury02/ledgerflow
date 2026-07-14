package com.ledgerflow.operations.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerflow.operations")
record OperationsProperties(
    @DefaultValue("20s") Duration drainTimeout,
    @DefaultValue("3s") Duration dependencyTimeout,
    @DefaultValue("true") boolean startupValidationEnabled) {

  OperationsProperties {
    requirePositive(drainTimeout, "drainTimeout");
    requirePositive(dependencyTimeout, "dependencyTimeout");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
