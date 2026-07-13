package com.ledgerflow.payments.api;

import java.util.UUID;

public record CapturedPayment(
    UUID paymentId,
    UUID orderId,
    long amountMinor,
    String currency,
    long version,
    CaptureAccountingStatus accountingStatus) {}
