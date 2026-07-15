package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationEffectIdentity;

public record ValidatedNotificationEvent(
    PaymentCapturedEventV1 event,
    String eventKey,
    String canonicalPayload,
    byte[] canonicalPayloadHash,
    NotificationEffectIdentity effectIdentity,
    String processingCorrelationId) {}
