package com.ledgerflow.orders.internal.application;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.orders.api.CreateOrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflowResult;
import com.ledgerflow.orders.api.PublicOrder;
import com.ledgerflow.orders.api.PublicPayment;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.orders.internal.domain.Order;
import com.ledgerflow.orders.internal.domain.OrderStatus;
import com.ledgerflow.orders.internal.domain.RequestFingerprint;
import com.ledgerflow.payments.api.InitializePayment;
import com.ledgerflow.payments.api.PaymentView;
import com.ledgerflow.payments.api.PaymentWorkflow;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublicOrderWorkflowService implements OrderWorkflow {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicOrderWorkflowService.class);
  private static final String CREATE_OPERATION = "CREATE_ORDER_V1";
  private static final String LEDGER_ACTOR = "orders:workflow";
  private static final int CONCURRENT_POLL_ATTEMPTS = 80;
  private static final long CONCURRENT_POLL_NANOS = 25_000_000L;

  private final OrderStore orderStore;
  private final PaymentWorkflow paymentWorkflow;
  private final LedgerPosting ledgerPosting;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<OrderWorkflowCheckpoint> checkpoints;
  private final OrderObservability observability;

  public PublicOrderWorkflowService(
      OrderStore orderStore,
      PaymentWorkflow paymentWorkflow,
      LedgerPosting ledgerPosting,
      TransactionTemplate transactionTemplate,
      ObjectMapper objectMapper,
      ObjectProvider<OrderWorkflowCheckpoint> checkpoints,
      OrderObservability observability) {
    this.orderStore = orderStore;
    this.paymentWorkflow = paymentWorkflow;
    this.ledgerPosting = ledgerPosting;
    this.transactionTemplate = transactionTemplate;
    this.objectMapper = objectMapper;
    this.checkpoints = checkpoints;
    this.observability = observability;
  }

  @Override
  public OrderWorkflowResult create(CreateOrderWorkflow command) {
    try (OrderObservability.WorkflowSpan span = observability.startWorkflow()) {
      try {
        Objects.requireNonNull(command, "command must not be null");
        Money money = new Money(command.amountMinor(), command.currency());
        IdempotencyKey idempotencyKey = new IdempotencyKey(command.idempotencyKey());
        byte[] keyHash = idempotencyKey.sha256();
        byte[] requestHash =
            RequestFingerprint.createWorkflow(
                command.clientReference(), money, command.paymentMethodReference());
        StartedWorkflow started =
            observability.inDatabaseSpan(
                "db.order.initialize",
                () ->
                    transactionTemplate.execute(
                        status -> start(command, money, keyHash, requestHash)));
        OrderWorkflowResult result =
            started.completedResult() != null
                ? started.completedResult()
                : advance(command, keyHash, requestHash, started.order(), started.payment());
        OrderObservability.Outcome outcome = outcome(result);
        observability.record(outcome);
        span.outcome(outcome);
        logResult(outcome);
        return result;
      } catch (IdempotencyConflictException exception) {
        observability.record(OrderObservability.Outcome.IDEMPOTENCY_CONFLICT);
        span.outcome(OrderObservability.Outcome.IDEMPOTENCY_CONFLICT);
        throw exception;
      } catch (RuntimeException exception) {
        observability.record(OrderObservability.Outcome.SYSTEM_FAILURE);
        span.outcome(OrderObservability.Outcome.SYSTEM_FAILURE);
        throw exception;
      }
    }
  }

  @Override
  public PublicOrder get(UUID orderId, String ownerSubject) {
    Order order =
        orderStore.findOwnedOrder(orderId, ownerSubject).orElseThrow(OrderNotFoundException::new);
    PaymentView payment = paymentWorkflow.findByOrderId(orderId).orElse(null);
    return view(order, payment);
  }

  private StartedWorkflow start(
      CreateOrderWorkflow command, Money money, byte[] keyHash, byte[] requestHash) {
    IdempotencyRecord record =
        orderStore.claimIdempotencyKey(
            command.ownerSubject(), CREATE_OPERATION, keyHash, requestHash);
    requireSameRequest(record, requestHash);
    if (!record.claimed()) {
      if ("COMPLETED".equals(record.state())) {
        return StartedWorkflow.completed(replay(record));
      }
      if (record.resourceId() == null) {
        throw new IdempotencyUnavailableException();
      }
      Order order = orderStore.findOrder(record.resourceId()).orElseThrow();
      PaymentView payment =
          paymentWorkflow
              .findByOrderId(order.orderId())
              .orElseThrow(IdempotencyUnavailableException::new);
      return StartedWorkflow.active(order, payment);
    }

    Order order =
        orderStore.insertWorkflowOrder(
            command.ownerSubject(), command.clientReference(), money, command.correlationId());
    PaymentView payment =
        paymentWorkflow.initialize(
            new InitializePayment(
                order.orderId(),
                money.amountMinor(),
                money.currency(),
                command.paymentMethodReference(),
                UUID.randomUUID()));
    orderStore.attachIdempotencyResource(
        command.ownerSubject(), CREATE_OPERATION, keyHash, order.orderId());
    observability.recordAfterCommit(OrderObservability.Outcome.CREATED);
    return StartedWorkflow.active(order, payment);
  }

  private OrderWorkflowResult advance(
      CreateOrderWorkflow command,
      byte[] keyHash,
      byte[] requestHash,
      Order order,
      PaymentView initialPayment) {
    PaymentView payment = initialPayment;
    for (int attempt = 0; attempt < CONCURRENT_POLL_ATTEMPTS; attempt++) {
      payment = paymentWorkflow.advance(payment.paymentId(), command.correlationId());
      switch (payment.status()) {
        case CAPTURE_CONFIRMED, CAPTURE_ACCOUNTED, CAPTURED -> {
          UUID capturedPaymentId = payment.paymentId();
          ledgerPosting.postPaymentCapture(
              new PostPaymentCaptureCommand(
                  capturedPaymentId, command.correlationId(), LEDGER_ACTOR));
          checkpoints
              .orderedStream()
              .forEach(checkpoint -> checkpoint.afterCaptureAccounting(capturedPaymentId));
          return finalizeResult(
              command,
              keyHash,
              requestHash,
              order.orderId(),
              capturedPaymentId,
              OrderStatus.COMPLETED,
              201);
        }
        case DECLINED, CAPTURE_DECLINED -> {
          return finalizeResult(
              command,
              keyHash,
              requestHash,
              order.orderId(),
              payment.paymentId(),
              OrderStatus.PAYMENT_DECLINED,
              201);
        }
        case AUTHORIZATION_RETRY_PENDING,
            AUTHORIZATION_UNKNOWN,
            CAPTURE_RETRY_PENDING,
            CAPTURE_UNKNOWN -> {
          return finalizeResult(
              command,
              keyHash,
              requestHash,
              order.orderId(),
              payment.paymentId(),
              OrderStatus.PAYMENT_RETRY_PENDING,
              202);
        }
        case FAILED -> {
          return finalizeResult(
              command,
              keyHash,
              requestHash,
              order.orderId(),
              payment.paymentId(),
              OrderStatus.FAILED,
              502);
        }
        case CREATED, AUTHORIZING, AUTHORIZED, CAPTURING -> {
          LockSupport.parkNanos(CONCURRENT_POLL_NANOS);
          if (Thread.currentThread().isInterrupted()) {
            throw new IdempotencyUnavailableException();
          }
        }
      }
    }
    throw new IdempotencyUnavailableException();
  }

  private OrderWorkflowResult finalizeResult(
      CreateOrderWorkflow command,
      byte[] keyHash,
      byte[] requestHash,
      UUID orderId,
      UUID paymentId,
      OrderStatus targetStatus,
      int responseStatus) {
    return observability.inDatabaseSpan(
        "db.order.finalize",
        () ->
            transactionTemplate.execute(
                status -> {
                  IdempotencyRecord record =
                      orderStore.lockIdempotencyKey(
                          command.ownerSubject(), CREATE_OPERATION, keyHash);
                  requireSameRequest(record, requestHash);
                  if ("COMPLETED".equals(record.state())) {
                    return replay(record);
                  }
                  PaymentView payment =
                      targetStatus == OrderStatus.COMPLETED
                          ? paymentWorkflow.finalizeCapture(paymentId, Instant.now())
                          : paymentWorkflow.findByOrderId(orderId).orElseThrow();
                  Order updated =
                      orderStore.transitionOrder(
                          orderId, OrderStatus.PAYMENT_PROCESSING, targetStatus);
                  PublicOrder response = view(updated, payment);
                  String location = location(orderId);
                  orderStore.completeIdempotencyKey(
                      command.ownerSubject(),
                      CREATE_OPERATION,
                      keyHash,
                      orderId,
                      responseStatus,
                      location,
                      serialize(response));
                  return new OrderWorkflowResult(response, responseStatus, location, false);
                }));
  }

  private OrderObservability.Outcome outcome(OrderWorkflowResult result) {
    if (result.replayed()) {
      return OrderObservability.Outcome.REPLAYED;
    }
    return switch (result.order().status()) {
      case "COMPLETED" -> OrderObservability.Outcome.COMPLETED;
      case "PAYMENT_DECLINED" -> OrderObservability.Outcome.DECLINED;
      case "PAYMENT_RETRY_PENDING" -> OrderObservability.Outcome.RETRY_PENDING;
      case "FAILED" -> OrderObservability.Outcome.FAILED;
      default -> OrderObservability.Outcome.SYSTEM_FAILURE;
    };
  }

  private void logResult(OrderObservability.Outcome outcome) {
    LOGGER
        .atInfo()
        .addKeyValue("event_code", "ORDER_WORKFLOW_RESULT")
        .addKeyValue("action", "order.workflow")
        .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
        .log("Order workflow reached a durable result");
  }

  private OrderWorkflowResult replay(IdempotencyRecord record) {
    if (!"COMPLETED".equals(record.state())
        || record.resourceId() == null
        || record.responseStatus() == null
        || record.responseLocation() == null
        || record.responseBody() == null) {
      throw new IdempotencyUnavailableException();
    }
    PublicOrder response = deserialize(record.responseBody());
    if (!record.resourceId().equals(response.orderId())) {
      throw new IdempotencyUnavailableException();
    }
    return new OrderWorkflowResult(
        response, record.responseStatus(), record.responseLocation(), true);
  }

  private void requireSameRequest(IdempotencyRecord record, byte[] requestHash) {
    if (!MessageDigest.isEqual(requestHash, record.requestHash())) {
      throw new IdempotencyConflictException();
    }
  }

  private PublicOrder view(Order order, PaymentView payment) {
    PublicPayment publicPayment =
        payment == null
            ? null
            : new PublicPayment(
                payment.paymentId(), payment.status().name(), safeFailureCode(payment));
    return new PublicOrder(
        order.orderId(),
        order.clientReference(),
        order.status().name(),
        order.amount().amountMinor(),
        order.amount().currency(),
        publicPayment,
        order.createdAt(),
        order.updatedAt());
  }

  private String serialize(PublicOrder order) {
    try {
      return objectMapper.writeValueAsString(order);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize the idempotent response", exception);
    }
  }

  private PublicOrder deserialize(String responseBody) {
    try {
      return objectMapper.readValue(responseBody, PublicOrder.class);
    } catch (JacksonException exception) {
      throw new IdempotencyUnavailableException();
    }
  }

  private String location(UUID orderId) {
    return "/api/v1/orders/" + orderId;
  }

  private String safeFailureCode(PaymentView payment) {
    return switch (payment.status()) {
      case DECLINED -> "AUTHORIZATION_DECLINED";
      case CAPTURE_DECLINED -> "CAPTURE_DECLINED";
      case AUTHORIZATION_RETRY_PENDING, CAPTURE_RETRY_PENDING ->
          "PAYMENT_PROVIDER_TEMPORARY_FAILURE";
      case AUTHORIZATION_UNKNOWN, CAPTURE_UNKNOWN -> "PROVIDER_OUTCOME_UNKNOWN";
      case FAILED -> "PROVIDER_PROTOCOL_ERROR";
      default -> null;
    };
  }

  private record StartedWorkflow(
      Order order, PaymentView payment, OrderWorkflowResult completedResult) {

    static StartedWorkflow active(Order order, PaymentView payment) {
      return new StartedWorkflow(order, payment, null);
    }

    static StartedWorkflow completed(OrderWorkflowResult result) {
      return new StartedWorkflow(null, null, result);
    }
  }
}
