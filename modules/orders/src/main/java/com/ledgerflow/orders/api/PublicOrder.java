package com.ledgerflow.orders.api;

import java.time.Instant;
import java.util.UUID;

public record PublicOrder(
    UUID orderId,
    String clientReference,
    String status,
    long amountMinor,
    String currency,
    PublicPayment payment,
    Instant createdAt,
    Instant updatedAt) {}
