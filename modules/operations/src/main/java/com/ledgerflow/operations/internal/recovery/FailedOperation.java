package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import java.time.Instant;
import java.util.UUID;

record FailedOperation(
    OperationType type,
    UUID sourceId,
    String status,
    String failureCode,
    String summary,
    long attemptCount,
    Instant failedAt,
    Instant updatedAt,
    boolean retryable,
    String originTraceparent) {

  String operationId() {
    return new OperationReference(type, sourceId).externalId();
  }
}
