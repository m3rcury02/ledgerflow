package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.PaymentCapturedEventV1;

public record ValidatedNotificationEvent(
    PaymentCapturedEventV1 event,
    String eventKey,
    String canonicalPayload,
    byte[] canonicalPayloadHash,
    String processingCorrelationId) {}
