package com.ledgerflow.operations.internal.recovery;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("operator_retry_commands")
public record OperatorRetryCommand(
    @Id UUID id,
    byte[] idempotencyKeyHash,
    byte[] requestHash,
    UUID operationId,
    String operationType,
    String status,
    String reason,
    boolean breakGlass,
    String approvalReference,
    String actorIssuer,
    String actorSubject,
    String actorClientId,
    String leaseOwner,
    Instant leaseUntil,
    String failureCode,
    Instant createdAt,
    Instant resolvedAt) {
  public OperatorRetryCommand withStatus(String status) {
    return new OperatorRetryCommand(
        id,
        idempotencyKeyHash,
        requestHash,
        operationId,
        operationType,
        status,
        reason,
        breakGlass,
        approvalReference,
        actorIssuer,
        actorSubject,
        actorClientId,
        leaseOwner,
        leaseUntil,
        failureCode,
        createdAt,
        resolvedAt);
  }

  public OperatorRetryCommand withLease(String leaseOwner, Instant leaseUntil) {
    return new OperatorRetryCommand(
        id,
        idempotencyKeyHash,
        requestHash,
        operationId,
        operationType,
        "IN_PROGRESS",
        reason,
        breakGlass,
        approvalReference,
        actorIssuer,
        actorSubject,
        actorClientId,
        leaseOwner,
        leaseUntil,
        failureCode,
        createdAt,
        resolvedAt);
  }

  public OperatorRetryCommand withResolution(
      String status, String failureCode, Instant resolvedAt) {
    return new OperatorRetryCommand(
        id,
        idempotencyKeyHash,
        requestHash,
        operationId,
        operationType,
        status,
        reason,
        breakGlass,
        approvalReference,
        actorIssuer,
        actorSubject,
        actorClientId,
        null,
        null,
        failureCode,
        createdAt,
        resolvedAt);
  }
}
