package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.AttemptHistory;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStore {

  Payment create(CreatePaymentCommand command, Instant now);

  Optional<Payment> find(UUID paymentId);

  Payment save(Payment expected, Payment updated);

  Payment saveWithHistory(Payment expected, Payment updated, AttemptHistory history);

  StartedAttempt startAttempt(
      Payment expected, PaymentStage stage, String correlationId, Instant now);

  void appendHistory(UUID paymentId, AttemptHistory history);
}
