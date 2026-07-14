package com.ledgerflow.notifications.internal.persistence;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterCatalogEntry(
    UUID eventId,
    String consumerName,
    String originalTopic,
    int originalPartition,
    long originalOffset,
    String eventKey,
    String validatedPayload,
    byte[] payloadHash,
    int payloadSize,
    String safeHeaders,
    String failureCode,
    String failureSummary,
    int attemptCount,
    boolean replayable,
    Instant catalogedAt) {}
