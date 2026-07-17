package com.ledgerflow.operations.api;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public record OperationRecoveryContext(
    UUID commandId,
    UUID sourceId,
    String correlationId,
    boolean firstExecution,
    boolean leaseTakeover,
    Duration recheckDelay,
    RecoveryLeaseGuard leaseGuard) {

  public OperationRecoveryContext {
    Objects.requireNonNull(commandId, "commandId must not be null");
    Objects.requireNonNull(sourceId, "sourceId must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");
    if (recheckDelay == null || recheckDelay.isNegative() || recheckDelay.isZero()) {
      throw new IllegalArgumentException("recheckDelay must be positive");
    }
    Objects.requireNonNull(leaseGuard, "leaseGuard must not be null");
  }
}
