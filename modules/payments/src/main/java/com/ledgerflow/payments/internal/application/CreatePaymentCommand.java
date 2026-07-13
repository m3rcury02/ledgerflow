package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.PaymentMoney;
import java.util.UUID;

public record CreatePaymentCommand(
    UUID orderId,
    PaymentMoney amount,
    String paymentMethodReference,
    UUID authorizationRequestId) {}
