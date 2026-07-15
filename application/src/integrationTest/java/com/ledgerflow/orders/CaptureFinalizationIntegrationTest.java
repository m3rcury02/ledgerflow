package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.orders.api.CreateOrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflowResult;
import com.ledgerflow.orders.internal.application.OrderWorkflowCheckpoint;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.orders.internal.domain.RequestFingerprint;
import com.ledgerflow.payments.internal.application.CreatePaymentCommand;
import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.application.StartedAttempt;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(CaptureFinalizationIntegrationTest.CheckpointConfiguration.class)
class CaptureFinalizationIntegrationTest extends PaymentIntegrationTestSupport {

  @Autowired private OrderWorkflow orderWorkflow;
  @Autowired private ToggleCheckpoint checkpoint;

  @BeforeEach
  void resetCheckpoint() {
    checkpoint.reset();
  }

  @Test
  void resumesAfterCrashFollowingLedgerAndOutboxCommitWithoutDuplicateEffects() {
    CreateOrderWorkflow command = command("crash-after-accounting-key", "pm_mock_success");
    checkpoint.arm();

    assertThatThrownBy(() -> orderWorkflow.create(command)).isInstanceOf(SimulatedCrash.class);
    assertThat(orderStatus()).isEqualTo("PAYMENT_PROCESSING");
    assertThat(paymentState()).isEqualTo("CAPTURE_ACCOUNTED");
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("ledger_entries")).isEqualTo(2);
    assertThat(count("outbox_events")).isOne();

    OrderWorkflowResult recovered = orderWorkflow.create(command);

    assertThat(recovered.responseStatus()).isEqualTo(201);
    assertThat(recovered.order().status()).isEqualTo("COMPLETED");
    assertThat(recovered.order().payment().status()).isEqualTo("CAPTURED");
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("ledger_entries")).isEqualTo(2);
    assertThat(count("outbox_events")).isOne();
  }

  @Test
  void concurrentWorkflowResumptionsConvergeWithoutDuplicateProviderOrFinancialEffects()
      throws Exception {
    CreateOrderWorkflow command = command("concurrent-workflow-key", "pm_mock_slow_response");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<OrderWorkflowResult> first =
          executor.submit(() -> createAfterStart(command, ready, start));
      Future<OrderWorkflowResult> second =
          executor.submit(() -> createAfterStart(command, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<OrderWorkflowResult> results =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results).extracting(result -> result.order().status()).containsOnly("COMPLETED");
      assertThat(results)
          .extracting(OrderWorkflowResult::replayed)
          .containsExactlyInAnyOrder(false, true);
    }

    UUID authorizationId =
        jdbcClient.sql("SELECT authorization_request_id FROM payments").query(UUID.class).single();
    UUID captureId =
        jdbcClient.sql("SELECT capture_request_id FROM payments").query(UUID.class).single();
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationId)).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureId)).isOne();
    assertThat(count("orders")).isOne();
    assertThat(count("payments")).isOne();
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("outbox_events")).isOne();
  }

  @Test
  void publicWorkflowRecoversProviderSuccessLostBeforeLocalPersistence() {
    CreateOrderWorkflow command = command("provider-success-crash-key", "pm_mock_success");
    Money money = new Money(command.amountMinor(), command.currency());
    UUID orderId =
        jdbcClient
            .sql(
                """
                INSERT INTO orders (
                    owner_subject, client_reference, amount_minor, currency, status,
                    initial_correlation_id
                ) VALUES (
                    :ownerSubject, :clientReference, :amountMinor, :currency,
                    'PAYMENT_PROCESSING', :correlationId
                )
                RETURNING id
                """)
            .param("ownerSubject", command.ownerSubject())
            .param("clientReference", command.clientReference())
            .param("amountMinor", money.amountMinor())
            .param("currency", money.currency())
            .param("correlationId", command.correlationId())
            .query(UUID.class)
            .single();
    jdbcClient
        .sql(
            """
            INSERT INTO idempotency_records (
                principal_scope, operation, key_hash, request_hash, state, resource_id
            ) VALUES (
                :ownerSubject, 'CREATE_ORDER_V1', :keyHash, :requestHash, 'IN_PROGRESS', :orderId
            )
            """)
        .param("ownerSubject", command.ownerSubject())
        .param("keyHash", new IdempotencyKey(command.idempotencyKey()).sha256())
        .param(
            "requestHash",
            RequestFingerprint.createWorkflow(
                command.clientReference(), money, command.paymentMethodReference()))
        .param("orderId", orderId)
        .update();
    Payment created =
        paymentWorkflow.create(
            new CreatePaymentCommand(
                orderId,
                new PaymentMoney(money.amountMinor(), money.currency()),
                command.paymentMethodReference(),
                UUID.randomUUID()));
    Payment authorizing = paymentStore.save(created, created.startAuthorization(Instant.now()));
    StartedAttempt started =
        paymentStore.startAttempt(
            authorizing, PaymentStage.AUTHORIZATION, command.correlationId(), Instant.now());
    ProviderResult result =
        paymentProvider.authorize(
            new PaymentProvider.AuthorizationRequest(
                started.payment().authorizationRequestId(),
                started.payment().paymentId(),
                started.payment().orderId(),
                started.payment().amount(),
                started.payment().paymentMethodReference(),
                command.correlationId()));
    assertThat(result).isInstanceOf(ProviderResult.Success.class);
    jdbcClient
        .sql(
            """
            UPDATE payments
            SET created_at = created_at - interval '10 seconds',
                updated_at = updated_at - interval '10 seconds'
            WHERE id = :paymentId
            """)
        .param("paymentId", created.paymentId())
        .update();

    OrderWorkflowResult recovered = orderWorkflow.create(command);

    assertThat(recovered.order().status()).isEqualTo("COMPLETED");
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId(created.paymentId()))).isOne();
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("outbox_events")).isOne();
  }

  private OrderWorkflowResult createAfterStart(
      CreateOrderWorkflow command, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent workflow did not start in time");
    }
    return orderWorkflow.create(command);
  }

  private CreateOrderWorkflow command(String key, String scenario) {
    return new CreateOrderWorkflow(
        "workflow-concurrency-customer",
        "workflow-correlation",
        "workflow-client-reference",
        25_990,
        "INR",
        scenario,
        key);
  }

  private String orderStatus() {
    return jdbcClient.sql("SELECT status FROM orders").query(String.class).single();
  }

  private String paymentState() {
    return jdbcClient.sql("SELECT state FROM payments").query(String.class).single();
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private UUID captureRequestId(UUID paymentId) {
    return jdbcClient
        .sql("SELECT capture_request_id FROM payments WHERE id = :paymentId")
        .param("paymentId", paymentId)
        .query(UUID.class)
        .single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CheckpointConfiguration {

    @Bean
    ToggleCheckpoint orderWorkflowCheckpoint() {
      return new ToggleCheckpoint();
    }
  }

  static final class ToggleCheckpoint implements OrderWorkflowCheckpoint {

    private final AtomicBoolean armed = new AtomicBoolean();

    void arm() {
      armed.set(true);
    }

    void reset() {
      armed.set(false);
    }

    @Override
    public void afterCaptureAccounting(UUID paymentId) {
      if (armed.compareAndSet(true, false)) {
        throw new SimulatedCrash();
      }
    }
  }

  static final class SimulatedCrash extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
