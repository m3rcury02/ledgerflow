package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class WorkflowDatabaseConstraintIntegrationTest extends LedgerIntegrationTestSupport {

  @Test
  void databaseRejectsCompletedOrderWithoutCaptureJournalAndOutbox() {
    Payment confirmed = confirmedPayment();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      jdbcClient
                          .sql(
                              """
                              UPDATE payments
                              SET state = 'CAPTURED', version = version + 1, updated_at = :now
                              WHERE id = :paymentId AND state = 'CAPTURE_CONFIRMED'
                              """)
                          .param("paymentId", confirmed.paymentId())
                          .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                          .update();
                      jdbcClient
                          .sql(
                              """
                              UPDATE orders
                              SET status = 'COMPLETED', version = version + 1,
                                  updated_at = statement_timestamp()
                              WHERE id = :orderId
                              """)
                          .param("orderId", confirmed.orderId())
                          .update();
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThat(paymentWorkflow.get(confirmed.paymentId()).state())
        .isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(
            jdbcClient
                .sql("SELECT status FROM orders WHERE id = :orderId")
                .param("orderId", confirmed.orderId())
                .query(String.class)
                .single())
        .isEqualTo("CREATED");
  }

  @Test
  void databaseRejectsTerminalOrderStateThatContradictsPaymentState() {
    Payment created = createPayment("pm_mock_success");

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        jdbcClient
                            .sql(
                                """
                                UPDATE orders
                                SET status = 'PAYMENT_DECLINED', version = version + 1,
                                    updated_at = statement_timestamp()
                                WHERE id = :orderId
                                """)
                            .param("orderId", created.orderId())
                            .update()))
        .isInstanceOf(DataAccessException.class);
  }
}
