package com.ledgerflow.messaging.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentCapturedEventV1(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID aggregateId,
    String correlationId,
    UUID causationId,
    Instant occurredAt,
    PaymentCapturedDataV1 data) {

  public static final String TYPE = "com.ledgerflow.payment.captured";
  public static final int SCHEMA_VERSION = 1;

  public PaymentCapturedEventV1 {
    if (eventId == null
        || aggregateId == null
        || causationId == null
        || occurredAt == null
        || data == null) {
      throw new IllegalArgumentException("event identity, time, and data are required");
    }
    if (!TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported payment-captured event type or schema");
    }
    if (!aggregateId.equals(data.paymentId())) {
      throw new IllegalArgumentException("aggregateId must equal the paymentId");
    }
    if (data.amountMinor() <= 0 || !"INR".equals(data.currency())) {
      throw new IllegalArgumentException("event money must be positive INR minor units");
    }
    if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("correlationId has an invalid format");
    }
  }
}
