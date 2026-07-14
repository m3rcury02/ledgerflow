package com.ledgerflow.messaging.internal.persistence;

import java.time.Instant;
import java.util.UUID;

public record OutboxRecord(
    UUID eventId,
    String topic,
    String eventKey,
    String eventType,
    int schemaVersion,
    UUID aggregateId,
    String correlationId,
    UUID causationId,
    Instant occurredAt,
    String payload,
    byte[] payloadHash,
    String traceparent,
    String tracestate,
    String leaseOwner,
    int cycleAttemptCount,
    long totalAttemptCount) {}
