package com.ledgerflow.payments.api;

import java.util.UUID;

public record InitializePayment(
    UUID orderId,
    long amountMinor,
    String currency,
    String paymentMethodReference,
    UUID authorizationRequestId) {}
