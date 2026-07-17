package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OperatorRecoveryViews {

  private OperatorRecoveryViews() {
    throw new UnsupportedOperationException("Views cannot be instantiated");
  }

  record Page(List<Summary> items, String nextCursor) {}

  record Summary(
      String operationId,
      OperationType operationType,
      String status,
      String failureCode,
      String summary,
      long attemptCount,
      Instant failedAt,
      Instant updatedAt,
      boolean retryable) {}

  record Detail(Summary operation, List<Attempt> attempts) {}

  record Attempt(
      String source,
      String action,
      int attemptNumber,
      String outcome,
      String failureCode,
      Instant recordedAt) {}

  record RetryStatus(
      UUID retryId,
      String operationId,
      String status,
      String attemptKind,
      int attemptNumber,
      String resultCode,
      String failureCode,
      Instant acceptedAt,
      Instant completedAt,
      boolean replayed) {}

  record Approval(UUID approvalId, String operationId, Instant createdAt, boolean replayed) {}
}
