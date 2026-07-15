package com.ledgerflow.payments.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentWorkflow {

  PaymentView initialize(InitializePayment command);

  PaymentView advance(UUID paymentId, String correlationId);

  Optional<PaymentView> findByOrderId(UUID orderId);

  PaymentView finalizeCapture(UUID paymentId, Instant finalizedAt);
}
