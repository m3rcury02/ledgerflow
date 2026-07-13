package com.ledgerflow.orders.internal.domain;

import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID orderId,
    String ownerSubject,
    String clientReference,
    Money amount,
    OrderStatus status,
    Instant createdAt,
    Instant updatedAt) {}
