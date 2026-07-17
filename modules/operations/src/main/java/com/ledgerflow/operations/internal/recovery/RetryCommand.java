package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import java.time.Instant;
import java.util.UUID;

record RetryCommand(
    UUID id,
    OperationType operationType,
    UUID sourceId,
    byte[] requestHash,
    String attemptKind,
    int attemptNumber,
    UUID approvalId,
    String status,
    String reason,
    ActorIdentity actor,
    String correlationId,
    String requestTraceparent,
    String originTraceparent,
    String leaseOwner,
    UUID leaseToken,
    Instant leaseUntil,
    Instant nextCheckAt,
    long version,
    String resultCode,
    String failureCode,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt) {}
