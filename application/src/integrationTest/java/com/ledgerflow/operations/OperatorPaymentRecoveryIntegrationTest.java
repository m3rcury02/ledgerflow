package com.ledgerflow.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.payments.api.PaymentWorkflow;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperatorPaymentRecoveryIntegrationTest extends PaymentIntegrationTestSupport {

  @Autowired private List<OperationRecoveryHandler> recoveryHandlers;
  @Autowired private PaymentWorkflow publicPaymentWorkflow;

  @Test
  void lookupFirstRecoveryReusesProviderIdentityAndFinalizesFinancialEffectsExactlyOnce() {
    Payment authorized = authorize("pm_mock_capture_timeout");
    Payment unknown = paymentWorkflow.capture(authorized.paymentId(), correlationId());
    assertThat(unknown.state()).isEqualTo(PaymentState.CAPTURE_UNKNOWN);
    UUID captureRequestId = unknown.captureRequestId();
    jdbcClient
        .sql("UPDATE orders SET status = 'PAYMENT_RETRY_PENDING' WHERE id = :orderId")
        .param("orderId", unknown.orderId())
        .update();

    OperationRecoveryContext context =
        new OperationRecoveryContext(
            UUID.randomUUID(),
            unknown.paymentId(),
            "operator-payment-recovery",
            true,
            false,
            java.time.Duration.ofSeconds(2),
            () -> assertThat(true).isTrue());
    OperationRecoveryResult first = handler().recover(context);
    OperationRecoveryResult replay = handler().recover(context);

    assertThat(first.status()).isEqualTo(OperationRecoveryResult.Status.COMPLETED);
    assertThat(replay.status()).isEqualTo(OperationRecoveryResult.Status.COMPLETED);
    assertThat(publicPaymentWorkflow.find(unknown.paymentId()).orElseThrow().status().name())
        .isEqualTo("CAPTURED");
    assertThat(
            jdbcClient
                .sql("SELECT status FROM orders WHERE id = :orderId")
                .param("orderId", unknown.orderId())
                .query(String.class)
                .single())
        .isEqualTo("COMPLETED");
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId)).isOne();
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("outbox_events")).isOne();
  }

  @Test
  void retryPendingRecoveryLooksUpBeforeOneSameIdentityProviderAttempt() {
    Payment created = createPayment("pm_mock_success");
    jdbcClient
        .sql(
            """
            UPDATE payments
            SET state = 'AUTHORIZATION_RETRY_PENDING', resume_stage = 'AUTHORIZATION',
                failure_code = 'PROVIDER_TEMPORARY_FAILURE', authorization_attempt_count = 2,
                version = version + 1, updated_at = statement_timestamp()
            WHERE id = :paymentId
            """)
        .param("paymentId", created.paymentId())
        .update();
    jdbcClient
        .sql("UPDATE orders SET status = 'PAYMENT_RETRY_PENDING' WHERE id = :orderId")
        .param("orderId", created.orderId())
        .update();

    OperationRecoveryResult result =
        handler()
            .recover(
                new OperationRecoveryContext(
                    UUID.randomUUID(),
                    created.paymentId(),
                    "operator-retry-pending",
                    true,
                    false,
                    java.time.Duration.ofSeconds(2),
                    () -> assertThat(true).isTrue()));

    assertThat(result.status()).isEqualTo(OperationRecoveryResult.Status.COMPLETED);
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT activity || ':' || outcome
                    FROM payment_attempt_history
                    WHERE payment_id = :paymentId
                    ORDER BY recorded_at, id
                    """)
                .param("paymentId", created.paymentId())
                .query(String.class)
                .list())
        .containsSequence("LOOKUP:STARTED", "LOOKUP:NOT_FOUND", "CALL:STARTED", "CALL:SUCCEEDED");
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("outbox_events")).isOne();
  }

  private OperationRecoveryHandler handler() {
    return recoveryHandlers.stream()
        .filter(handler -> handler.operationType() == OperationType.PAYMENT)
        .findFirst()
        .orElseThrow();
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
