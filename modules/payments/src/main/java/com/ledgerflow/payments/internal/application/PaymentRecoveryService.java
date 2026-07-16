package com.ledgerflow.payments.internal.application;

import com.ledgerflow.operations.api.OperationRetryHandler;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PaymentRecoveryService implements OperationRetryHandler {

  private final ObjectProvider<PaymentWorkflowService> workflowServiceProvider;
  private final PaymentStore paymentStore;
  private final Clock clock;

  public PaymentRecoveryService(
      ObjectProvider<PaymentWorkflowService> workflowServiceProvider,
      PaymentStore paymentStore,
      Clock clock) {
    this.workflowServiceProvider = workflowServiceProvider;
    this.paymentStore = paymentStore;
    this.clock = clock;
  }

  @Override
  public String getSupportedOperationType() {
    return "PAYMENT";
  }

  @Override
  public void handleRetry(
      UUID commandId, UUID operationId, String idempotencyKeyHash, boolean isBreakGlass) {
    String correlationId = "retry-" + commandId.toString();
    Payment payment =
        paymentStore
            .find(operationId)
            .orElseThrow(() -> new IllegalStateException("Payment not found"));

    PaymentWorkflowService workflowService = workflowServiceProvider.getIfAvailable();
    if (workflowService == null) {
      throw new IllegalStateException("Payment provider is not configured");
    }

    if (payment.state() == PaymentState.FAILED) {
      Payment resumed = paymentStore.save(payment, payment.operatorResume(clock.instant()));
      if (resumed.state() == PaymentState.AUTHORIZATION_RETRY_PENDING) {
        workflowService.authorize(operationId, correlationId);
      } else if (resumed.state() == PaymentState.CAPTURE_RETRY_PENDING) {
        workflowService.capture(operationId, correlationId);
      }
    } else {
      workflowService.recover(operationId, correlationId);
    }
  }
}
