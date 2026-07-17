package com.ledgerflow.orders.internal.application;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.orders.internal.domain.Order;
import com.ledgerflow.orders.internal.domain.OrderStatus;
import com.ledgerflow.payments.api.PaymentView;
import com.ledgerflow.payments.api.PaymentWorkflow;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class PaymentOperationRecoveryHandler implements OperationRecoveryHandler {

  private static final String LEDGER_ACTOR = "orders:operator-recovery";

  private final PaymentWorkflow paymentWorkflow;
  private final LedgerPosting ledgerPosting;
  private final OrderStore orderStore;
  private final TransactionTemplate transactionTemplate;

  PaymentOperationRecoveryHandler(
      PaymentWorkflow paymentWorkflow,
      LedgerPosting ledgerPosting,
      OrderStore orderStore,
      TransactionTemplate transactionTemplate) {
    this.paymentWorkflow = paymentWorkflow;
    this.ledgerPosting = ledgerPosting;
    this.orderStore = orderStore;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public OperationType operationType() {
    return OperationType.PAYMENT;
  }

  @Override
  public OperationRecoveryResult recover(OperationRecoveryContext context) {
    context.leaseGuard().requireCurrent();
    PaymentView current = paymentWorkflow.find(context.sourceId()).orElse(null);
    if (current == null) {
      return OperationRecoveryResult.failed("PAYMENT_NOT_FOUND");
    }
    PaymentView payment = progress(current, context.correlationId());
    return switch (payment.status()) {
      case CAPTURE_CONFIRMED, CAPTURE_ACCOUNTED, CAPTURED -> completeCapture(context, payment);
      case DECLINED, CAPTURE_DECLINED -> completeDecline(context, payment);
      case AUTHORIZATION_RETRY_PENDING,
          AUTHORIZATION_UNKNOWN,
          CAPTURE_RETRY_PENDING,
          CAPTURE_UNKNOWN ->
          OperationRecoveryResult.failed("PAYMENT_RECOVERY_PENDING");
      case FAILED -> completeFailure(context, payment);
      case CREATED, AUTHORIZING, AUTHORIZED, CAPTURING ->
          OperationRecoveryResult.waiting("PAYMENT_OPERATION_IN_PROGRESS", context.recheckDelay());
    };
  }

  private PaymentView progress(PaymentView current, String correlationId) {
    PaymentView payment =
        switch (current.status()) {
          case AUTHORIZING,
              AUTHORIZATION_RETRY_PENDING,
              AUTHORIZATION_UNKNOWN,
              CAPTURING,
              CAPTURE_RETRY_PENDING,
              CAPTURE_UNKNOWN ->
              paymentWorkflow.recover(current.paymentId(), correlationId);
          case AUTHORIZED -> paymentWorkflow.advance(current.paymentId(), correlationId);
          case CREATED,
              DECLINED,
              CAPTURE_CONFIRMED,
              CAPTURE_ACCOUNTED,
              CAPTURED,
              CAPTURE_DECLINED,
              FAILED ->
              current;
        };
    if (payment.status() == com.ledgerflow.payments.api.PaymentStatus.AUTHORIZED) {
      return paymentWorkflow.advance(payment.paymentId(), correlationId);
    }
    return payment;
  }

  private OperationRecoveryResult completeCapture(
      OperationRecoveryContext context, PaymentView payment) {
    transactionTemplate.executeWithoutResult(
        status -> {
          context.leaseGuard().requireCurrent();
          ledgerPosting.postPaymentCapture(
              new PostPaymentCaptureCommand(
                  payment.paymentId(), context.correlationId(), LEDGER_ACTOR));
        });
    transactionTemplate.executeWithoutResult(
        status -> {
          context.leaseGuard().requireCurrent();
          paymentWorkflow.finalizeCapture(payment.paymentId(), Instant.now());
          transitionOrder(payment.orderId(), OrderStatus.COMPLETED);
        });
    return OperationRecoveryResult.completed("PAYMENT_CAPTURE_COMPLETED");
  }

  private OperationRecoveryResult completeDecline(
      OperationRecoveryContext context, PaymentView payment) {
    transactionTemplate.executeWithoutResult(
        status -> {
          context.leaseGuard().requireCurrent();
          transitionOrder(payment.orderId(), OrderStatus.PAYMENT_DECLINED);
        });
    return OperationRecoveryResult.completed("PAYMENT_DECLINE_RECONCILED");
  }

  private OperationRecoveryResult completeFailure(
      OperationRecoveryContext context, PaymentView payment) {
    transactionTemplate.executeWithoutResult(
        status -> {
          context.leaseGuard().requireCurrent();
          transitionOrder(payment.orderId(), OrderStatus.FAILED);
        });
    return OperationRecoveryResult.failed("PAYMENT_RECOVERY_FAILED");
  }

  private void transitionOrder(java.util.UUID orderId, OrderStatus target) {
    Order current = orderStore.findOrder(orderId).orElseThrow();
    if (current.status() == target) {
      return;
    }
    if (current.status() != OrderStatus.PAYMENT_PROCESSING
        && current.status() != OrderStatus.PAYMENT_RETRY_PENDING) {
      throw new IllegalStateException("Order is not in a recoverable payment state");
    }
    orderStore.transitionOrder(orderId, current.status(), target);
  }
}
