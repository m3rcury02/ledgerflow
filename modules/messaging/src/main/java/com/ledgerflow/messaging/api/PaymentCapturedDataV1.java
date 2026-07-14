package com.ledgerflow.messaging.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentCapturedDataV1(
    UUID orderId,
    UUID paymentId,
    UUID ledgerTransactionId,
    long amountMinor,
    String currency,
    Instant capturedAt) {}
