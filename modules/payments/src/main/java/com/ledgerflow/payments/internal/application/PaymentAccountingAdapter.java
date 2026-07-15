package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.api.CaptureAccountingStatus;
import com.ledgerflow.payments.api.CapturedPayment;
import com.ledgerflow.payments.api.PaymentAccounting;
import com.ledgerflow.payments.api.PaymentNotReadyForAccountingException;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentAccountingAdapter implements PaymentAccounting {

  private final PaymentStore paymentStore;
  private final PaymentMetrics metrics;

  public PaymentAccountingAdapter(PaymentStore paymentStore, PaymentMetrics metrics) {
    this.paymentStore = paymentStore;
    this.metrics = metrics;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public CapturedPayment lockCapture(UUID paymentId) {
    Payment payment = paymentStore.lock(paymentId);
    CaptureAccountingStatus status = accountingStatus(payment.state());
    return new CapturedPayment(
        payment.paymentId(),
        payment.orderId(),
        payment.amount().amountMinor(),
        payment.amount().currency(),
        payment.captureRequestId(),
        payment.version(),
        status);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void markCaptureAccounted(UUID paymentId, long expectedVersion, Instant accountedAt) {
    Payment payment = paymentStore.lock(paymentId);
    if (payment.version() != expectedVersion) {
      throw new ConcurrentPaymentModificationException();
    }
    Payment accounted = paymentStore.save(payment, payment.captureAccounted(accountedAt));
    metrics.recordStateAfterCommit(accounted.state());
  }

  private CaptureAccountingStatus accountingStatus(PaymentState state) {
    return switch (state) {
      case CAPTURE_CONFIRMED -> CaptureAccountingStatus.CONFIRMED;
      case CAPTURE_ACCOUNTED, CAPTURED -> CaptureAccountingStatus.ACCOUNTED;
      default -> throw new PaymentNotReadyForAccountingException();
    };
  }
}
