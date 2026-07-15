package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.api.InitializePayment;
import com.ledgerflow.payments.api.PaymentStatus;
import com.ledgerflow.payments.api.PaymentView;
import com.ledgerflow.payments.api.PaymentWorkflow;
import com.ledgerflow.payments.api.PaymentWorkflowUnavailableException;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.payments.internal.provider.PaymentProviderProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentWorkflowAdapter implements PaymentWorkflow {

  private static final int MAX_ADVANCE_STEPS = 6;

  private final PaymentStore paymentStore;
  private final ObjectProvider<PaymentWorkflowService> workflowProvider;
  private final ObjectProvider<PaymentProviderProperties> propertiesProvider;
  private final Clock clock;

  public PaymentWorkflowAdapter(
      PaymentStore paymentStore,
      ObjectProvider<PaymentWorkflowService> workflowProvider,
      ObjectProvider<PaymentProviderProperties> propertiesProvider) {
    this.paymentStore = paymentStore;
    this.workflowProvider = workflowProvider;
    this.propertiesProvider = propertiesProvider;
    this.clock = Clock.systemUTC();
  }

  @Override
  public PaymentView initialize(InitializePayment command) {
    PaymentWorkflowService workflow = workflow();
    Payment payment =
        workflow.create(
            new CreatePaymentCommand(
                command.orderId(),
                new PaymentMoney(command.amountMinor(), command.currency()),
                command.paymentMethodReference(),
                command.authorizationRequestId()));
    return view(payment);
  }

  @Override
  public PaymentView advance(UUID paymentId, String correlationId) {
    PaymentWorkflowService workflow = workflow();
    Payment current = workflow.get(paymentId);
    for (int step = 0; step < MAX_ADVANCE_STEPS; step++) {
      if (activeCallMayStillBeRunning(current)) {
        return view(current);
      }
      Payment previous = current;
      try {
        current = advanceOne(workflow, current, correlationId);
      } catch (ConcurrentPaymentModificationException exception) {
        current = workflow.get(paymentId);
        continue;
      }
      if (isPublicOutcome(current.state()) || unchangedUnknown(previous, current)) {
        return view(current);
      }
    }
    return view(current);
  }

  @Override
  public Optional<PaymentView> findByOrderId(UUID orderId) {
    return paymentStore.findByOrderId(orderId).map(this::view);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentView finalizeCapture(UUID paymentId, Instant finalizedAt) {
    Payment current = paymentStore.lock(paymentId);
    if (current.state() == PaymentState.CAPTURED) {
      return view(current);
    }
    Payment finalized = paymentStore.save(current, current.captureFinalized(finalizedAt));
    return view(finalized);
  }

  private Payment advanceOne(
      PaymentWorkflowService workflow, Payment payment, String correlationId) {
    return switch (payment.state()) {
      case CREATED, AUTHORIZING, AUTHORIZATION_RETRY_PENDING, AUTHORIZATION_UNKNOWN ->
          workflow.authorize(payment.paymentId(), correlationId);
      case AUTHORIZED, CAPTURING, CAPTURE_RETRY_PENDING, CAPTURE_UNKNOWN ->
          workflow.capture(payment.paymentId(), correlationId);
      case DECLINED, CAPTURE_DECLINED, CAPTURE_CONFIRMED, CAPTURE_ACCOUNTED, CAPTURED, FAILED ->
          payment;
    };
  }

  private boolean isPublicOutcome(PaymentState state) {
    return switch (state) {
      case DECLINED,
          AUTHORIZATION_RETRY_PENDING,
          CAPTURE_CONFIRMED,
          CAPTURE_ACCOUNTED,
          CAPTURED,
          CAPTURE_DECLINED,
          CAPTURE_RETRY_PENDING,
          FAILED ->
          true;
      default -> false;
    };
  }

  private boolean unchangedUnknown(Payment previous, Payment current) {
    return previous.state() == current.state()
        && (current.state() == PaymentState.AUTHORIZATION_UNKNOWN
            || current.state() == PaymentState.CAPTURE_UNKNOWN);
  }

  private boolean activeCallMayStillBeRunning(Payment payment) {
    if (payment.state() != PaymentState.AUTHORIZING && payment.state() != PaymentState.CAPTURING) {
      return false;
    }
    PaymentProviderProperties properties = propertiesProvider.getIfAvailable();
    if (properties == null) {
      throw new PaymentWorkflowUnavailableException();
    }
    return payment.updatedAt().plus(properties.activeOperationTimeout()).isAfter(clock.instant());
  }

  private PaymentWorkflowService workflow() {
    PaymentWorkflowService workflow = workflowProvider.getIfAvailable();
    if (workflow == null) {
      throw new PaymentWorkflowUnavailableException();
    }
    return workflow;
  }

  private PaymentView view(Payment payment) {
    return new PaymentView(
        payment.paymentId(),
        payment.orderId(),
        payment.amount().amountMinor(),
        payment.amount().currency(),
        PaymentStatus.valueOf(payment.state().name()),
        payment.authorizationRequestId(),
        payment.captureRequestId(),
        payment.failureCode(),
        payment.createdAt(),
        payment.updatedAt());
  }
}
