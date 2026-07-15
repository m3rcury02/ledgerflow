package com.ledgerflow.payments.internal.persistence;

import com.ledgerflow.payments.internal.application.ConcurrentPaymentModificationException;
import com.ledgerflow.payments.internal.application.CreatePaymentCommand;
import com.ledgerflow.payments.internal.application.PaymentNotFoundException;
import com.ledgerflow.payments.internal.application.PaymentStore;
import com.ledgerflow.payments.internal.application.StartedAttempt;
import com.ledgerflow.payments.internal.domain.AttemptActivity;
import com.ledgerflow.payments.internal.domain.AttemptHistory;
import com.ledgerflow.payments.internal.domain.AttemptOutcome;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.payments.internal.domain.PaymentState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentStore implements PaymentStore {

  private final JdbcClient jdbcClient;

  public JdbcPaymentStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  @Transactional
  public Payment create(CreatePaymentCommand command, Instant now) {
    return jdbcClient
        .sql(
            """
            INSERT INTO payments (
                order_id, amount_minor, currency, state, payment_method_reference,
                authorization_request_id, created_at, updated_at
            ) VALUES (
                :orderId, :amountMinor, :currency, 'CREATED', :paymentMethodReference,
                :authorizationRequestId, :now, :now
            )
            RETURNING *
            """)
        .param("orderId", command.orderId())
        .param("amountMinor", command.amount().amountMinor())
        .param("currency", command.amount().currency())
        .param("paymentMethodReference", command.paymentMethodReference())
        .param("authorizationRequestId", command.authorizationRequestId())
        .param("now", databaseTimestamp(now))
        .query(this::mapPayment)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Payment> find(UUID paymentId) {
    return jdbcClient
        .sql("SELECT * FROM payments WHERE id = :paymentId")
        .param("paymentId", paymentId)
        .query(this::mapPayment)
        .optional();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Payment> findByOrderId(UUID orderId) {
    return jdbcClient
        .sql("SELECT * FROM payments WHERE order_id = :orderId")
        .param("orderId", orderId)
        .query(this::mapPayment)
        .optional();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Payment lock(UUID paymentId) {
    return jdbcClient
        .sql("SELECT * FROM payments WHERE id = :paymentId FOR UPDATE")
        .param("paymentId", paymentId)
        .query(this::mapPayment)
        .optional()
        .orElseThrow(PaymentNotFoundException::new);
  }

  @Override
  @Transactional
  public Payment save(Payment expected, Payment updated) {
    updatePayment(expected, updated);
    return findRequired(expected.paymentId());
  }

  @Override
  @Transactional
  public Payment saveWithHistory(Payment expected, Payment updated, AttemptHistory history) {
    updatePayment(expected, updated);
    insertHistory(expected.paymentId(), history);
    return findRequired(expected.paymentId());
  }

  @Override
  @Transactional
  public StartedAttempt startAttempt(
      Payment expected, PaymentStage stage, String correlationId, Instant now) {
    String countColumn =
        switch (stage) {
          case AUTHORIZATION -> "authorization_attempt_count";
          case CAPTURE -> "capture_attempt_count";
        };
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE payments
                SET %s = %s + 1, version = version + 1, updated_at = :now
                WHERE id = :paymentId AND version = :version AND state = :state
                """
                    .formatted(countColumn, countColumn))
            .param("now", databaseTimestamp(now))
            .param("paymentId", expected.paymentId())
            .param("version", expected.version())
            .param("state", expected.state().name())
            .update();
    if (updated != 1) {
      throw new ConcurrentPaymentModificationException();
    }
    Payment current = findRequired(expected.paymentId());
    int attemptNumber = current.attemptCount(stage);
    insertHistory(
        current.paymentId(),
        new AttemptHistory(
            stage,
            AttemptActivity.CALL,
            attemptNumber,
            AttemptOutcome.STARTED,
            current.requestId(stage),
            null,
            null,
            correlationId,
            now));
    return new StartedAttempt(current, attemptNumber);
  }

  @Override
  @Transactional
  public void appendHistory(UUID paymentId, AttemptHistory history) {
    insertHistory(paymentId, history);
  }

  private void updatePayment(Payment expected, Payment updated) {
    int count =
        jdbcClient
            .sql(
                """
                UPDATE payments
                SET state = :state,
                    resume_stage = :resumeStage,
                    payment_method_reference = :paymentMethodReference,
                    capture_request_id = :captureRequestId,
                    provider_authorization_id = :providerAuthorizationId,
                    provider_capture_id = :providerCaptureId,
                    failure_code = :failureCode,
                    updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :paymentId AND version = :version AND state = :expectedState
                """)
            .param("state", updated.state().name())
            .param(
                "resumeStage",
                updated.resumeStage() == null ? null : updated.resumeStage().name(),
                Types.VARCHAR)
            .param("paymentMethodReference", updated.paymentMethodReference(), Types.VARCHAR)
            .param("captureRequestId", updated.captureRequestId(), Types.OTHER)
            .param("providerAuthorizationId", updated.providerAuthorizationId(), Types.VARCHAR)
            .param("providerCaptureId", updated.providerCaptureId(), Types.VARCHAR)
            .param("failureCode", updated.failureCode(), Types.VARCHAR)
            .param("updatedAt", databaseTimestamp(updated.updatedAt()))
            .param("paymentId", expected.paymentId())
            .param("version", expected.version())
            .param("expectedState", expected.state().name())
            .update();
    if (count != 1) {
      throw new ConcurrentPaymentModificationException();
    }
  }

  private void insertHistory(UUID paymentId, AttemptHistory history) {
    jdbcClient
        .sql(
            """
            INSERT INTO payment_attempt_history (
                payment_id, stage, activity, attempt_number, outcome, provider_request_id,
                provider_reference, failure_code, correlation_id, recorded_at
            ) VALUES (
                :paymentId, :stage, :activity, :attemptNumber, :outcome, :providerRequestId,
                :providerReference, :failureCode, :correlationId, :recordedAt
            )
            """)
        .param("paymentId", paymentId)
        .param("stage", history.stage().name())
        .param("activity", history.activity().name())
        .param("attemptNumber", history.attemptNumber())
        .param("outcome", history.outcome().name())
        .param("providerRequestId", history.providerRequestId())
        .param("providerReference", history.providerReference(), Types.VARCHAR)
        .param("failureCode", history.failureCode(), Types.VARCHAR)
        .param("correlationId", history.correlationId())
        .param("recordedAt", databaseTimestamp(history.recordedAt()))
        .update();
  }

  private OffsetDateTime databaseTimestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private Payment findRequired(UUID paymentId) {
    return jdbcClient
        .sql("SELECT * FROM payments WHERE id = :paymentId")
        .param("paymentId", paymentId)
        .query(this::mapPayment)
        .single();
  }

  private Payment mapPayment(ResultSet resultSet, int rowNumber) throws SQLException {
    String resumeStage = resultSet.getString("resume_stage");
    return new Payment(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("order_id", UUID.class),
        new PaymentMoney(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
        PaymentState.valueOf(resultSet.getString("state")),
        resumeStage == null ? null : PaymentStage.valueOf(resumeStage),
        resultSet.getString("payment_method_reference"),
        resultSet.getObject("authorization_request_id", UUID.class),
        resultSet.getObject("capture_request_id", UUID.class),
        resultSet.getString("provider_authorization_id"),
        resultSet.getString("provider_capture_id"),
        resultSet.getString("failure_code"),
        resultSet.getInt("authorization_attempt_count"),
        resultSet.getInt("capture_attempt_count"),
        resultSet.getLong("version"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
