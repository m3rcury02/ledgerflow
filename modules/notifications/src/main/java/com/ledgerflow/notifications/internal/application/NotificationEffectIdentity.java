package com.ledgerflow.notifications.internal.application;

import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record NotificationEffectIdentity(
    String effectType,
    short version,
    UUID effectKey,
    UUID orderId,
    UUID paymentId,
    UUID sourceCausationId,
    long amountMinor,
    String currency,
    Instant sourceOccurredAt) {

  public static final String PAYMENT_CAPTURED_EFFECT = "PAYMENT_CAPTURED_NOTIFICATION";
  public static final short PAYMENT_CAPTURED_VERSION = 1;

  public NotificationEffectIdentity {
    Objects.requireNonNull(effectType, "effect type must not be null");
    Objects.requireNonNull(effectKey, "effect key must not be null");
    Objects.requireNonNull(orderId, "order ID must not be null");
    Objects.requireNonNull(paymentId, "payment ID must not be null");
    Objects.requireNonNull(sourceCausationId, "source causation ID must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(sourceOccurredAt, "source occurrence time must not be null");
  }

  public static NotificationEffectIdentity from(PaymentCapturedEventV1 event) {
    Objects.requireNonNull(event, "event must not be null");
    PaymentCapturedDataV1 data = event.data();
    return new NotificationEffectIdentity(
        PAYMENT_CAPTURED_EFFECT,
        PAYMENT_CAPTURED_VERSION,
        data.ledgerTransactionId(),
        data.orderId(),
        data.paymentId(),
        event.causationId(),
        data.amountMinor(),
        data.currency(),
        data.capturedAt().truncatedTo(ChronoUnit.MICROS));
  }
}
