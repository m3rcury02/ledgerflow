package com.ledgerflow.payments.api;

import java.time.Instant;
import java.util.UUID;

public interface PaymentAccounting {

  CapturedPayment lockCapture(UUID paymentId);

  void markCaptureAccounted(UUID paymentId, long expectedVersion, Instant accountedAt);
}
