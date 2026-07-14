package com.ledgerflow.payments.api;

import java.util.UUID;

public record CapturedPayment(
    UUID paymentId,
    UUID orderId,
    long amountMinor,
    String currency,
    UUID captureRequestId,
    long version,
    CaptureAccountingStatus accountingStatus) {}
