package com.ledgerflow.orders.api;

public record CreateOrderWorkflow(
    String ownerSubject,
    String correlationId,
    String clientReference,
    long amountMinor,
    String currency,
    String paymentMethodReference,
    String idempotencyKey) {}
