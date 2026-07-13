package com.ledgerflow.payments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.payments.internal.application.ConcurrentPaymentModificationException;
import com.ledgerflow.payments.internal.application.CreatePaymentCommand;
import com.ledgerflow.payments.internal.domain.AttemptActivity;
import com.ledgerflow.payments.internal.domain.AttemptHistory;
import com.ledgerflow.payments.internal.domain.AttemptOutcome;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentRepositoryIntegrationTest extends PaymentIntegrationTestSupport {

  @Test
  void rejectsAnOptimisticUpdateFromAStalePaymentVersion() {
    Payment created = createPayment("pm_mock_success");
    Payment first = created.startAuthorization(Instant.now());
    paymentStore.save(created, first);

    assertThatThrownBy(() -> paymentStore.save(created, first))
        .isInstanceOf(ConcurrentPaymentModificationException.class);
  }

  @Test
  void databaseRejectsPaymentMoneyThatDoesNotMatchItsOrder() {
    Payment valid = createPayment("pm_mock_success");

    assertThatThrownBy(
            () ->
                paymentStore.create(
                    new CreatePaymentCommand(
                        valid.orderId(),
                        new PaymentMoney(valid.amount().amountMinor() + 1, "INR"),
                        "pm_mock_success",
                        UUID.randomUUID()),
                    Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseEnforcesUniqueProviderRequestIds() {
    Payment first = createPayment("pm_mock_success");
    Payment secondOrder = createPayment("pm_mock_success");

    assertThatThrownBy(
            () ->
                paymentStore.create(
                    new CreatePaymentCommand(
                        secondOrder.orderId(),
                        secondOrder.amount(),
                        "pm_mock_success",
                        first.authorizationRequestId()),
                    Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void paymentAttemptHistoryCannotBeUpdatedOrDeleted() {
    Payment created = createPayment("pm_mock_success");
    paymentStore.appendHistory(
        created.paymentId(),
        new AttemptHistory(
            PaymentStage.AUTHORIZATION,
            AttemptActivity.CALL,
            1,
            AttemptOutcome.STARTED,
            created.authorizationRequestId(),
            null,
            null,
            correlationId(),
            Instant.now()));

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        UPDATE payment_attempt_history
                        SET correlation_id = 'changed'
                        WHERE payment_id = :paymentId
                        """)
                    .param("paymentId", created.paymentId())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("DELETE FROM payment_attempt_history WHERE payment_id = :paymentId")
                    .param("paymentId", created.paymentId())
                    .update())
        .isInstanceOf(DataAccessException.class);
  }
}
