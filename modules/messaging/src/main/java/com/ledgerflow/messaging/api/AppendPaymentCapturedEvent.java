package com.ledgerflow.messaging.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AppendPaymentCapturedEvent(
    UUID paymentId,
    UUID orderId,
    UUID ledgerTransactionId,
    long amountMinor,
    String currency,
    UUID causationId,
    String correlationId,
    Instant occurredAt) {

  public AppendPaymentCapturedEvent {
    Objects.requireNonNull(paymentId, "paymentId must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId must not be null");
    Objects.requireNonNull(causationId, "causationId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    if (!"INR".equals(currency)) {
      throw new IllegalArgumentException("currency must be INR for the MVP");
    }
    if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("correlationId has an invalid format");
    }
  }
}
