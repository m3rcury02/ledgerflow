package com.ledgerflow.orders.internal.application;

import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;

public record CreateOrderCommand(
    String ownerSubject,
    String correlationId,
    String clientReference,
    Money amount,
    IdempotencyKey idempotencyKey) {}
