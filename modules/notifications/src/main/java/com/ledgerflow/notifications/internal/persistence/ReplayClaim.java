package com.ledgerflow.notifications.internal.persistence;

import java.util.UUID;

public record ReplayClaim(
    UUID deadLetterRecordId,
    UUID replayRequestId,
    UUID eventId,
    String eventKey,
    String validatedPayload,
    String leaseOwner,
    String actor,
    String reason,
    String correlationId) {}
