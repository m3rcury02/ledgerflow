package com.ledgerflow.payments.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(
    UUID paymentId,
    UUID orderId,
    long amountMinor,
    String currency,
    PaymentStatus status,
    UUID authorizationRequestId,
    UUID captureRequestId,
    String failureCode,
    Instant createdAt,
    Instant updatedAt) {}
