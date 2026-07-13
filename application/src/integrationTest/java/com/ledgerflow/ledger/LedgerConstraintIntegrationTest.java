package com.ledgerflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class LedgerConstraintIntegrationTest extends LedgerIntegrationTestSupport {

  @Test
  void deferredConstraintRejectsUnbalancedEntriesAndRollsBackPaymentAccounting() {
    Payment confirmed = confirmedPayment();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      markAccounted(confirmed);
                      UUID transactionId = insertCaptureHeader(confirmed);
                      insertEntry(transactionId, "PAYMENT_CLEARING", "D", 10_000);
                      insertEntry(transactionId, "MERCHANT_PAYABLE", "C", 9_999);
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThat(paymentWorkflow.get(confirmed.paymentId()).state())
        .isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(transactionCount()).isZero();
    assertThat(entryCount()).isZero();
  }

  @Test
  void deferredConstraintRejectsJournalWithFewerThanTwoEntries() {
    Payment confirmed = confirmedPayment();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      markAccounted(confirmed);
                      UUID transactionId = insertCaptureHeader(confirmed);
                      insertEntry(transactionId, "PAYMENT_CLEARING", "D", 10_000);
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThat(paymentWorkflow.get(confirmed.paymentId()).state())
        .isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(transactionCount()).isZero();
  }

  @Test
  void postedTransactionsAndEntriesRejectUpdatesAndDeletes() {
    PostedJournal posted = postCapture(confirmedPayment());

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        UPDATE ledger_transactions
                        SET description = 'Changed after posting'
                        WHERE id = :transactionId
                        """)
                    .param("transactionId", posted.transactionId())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("DELETE FROM ledger_transactions WHERE id = :transactionId")
                    .param("transactionId", posted.transactionId())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        UPDATE ledger_entries
                        SET amount_minor = amount_minor + 1
                        WHERE transaction_id = :transactionId
                        """)
                    .param("transactionId", posted.transactionId())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("DELETE FROM ledger_entries WHERE transaction_id = :transactionId")
                    .param("transactionId", posted.transactionId())
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThat(transactionCount()).isOne();
    assertThat(entryCount()).isEqualTo(2);
  }

  @Test
  void databaseRejectsNonPositiveMinorUnitAmounts() {
    PostedJournal posted = postCapture(confirmedPayment());

    assertThatThrownBy(() -> insertEntry(posted.transactionId(), "PAYMENT_CLEARING", "D", 0))
        .isInstanceOf(DataAccessException.class);
    assertThat(entryCount()).isEqualTo(2);
  }

  @Test
  void databaseRejectsASecondCaptureJournalForTheSamePayment() {
    Payment confirmed = confirmedPayment();
    postCapture(confirmed);

    assertThatThrownBy(() -> insertCaptureHeader(confirmed))
        .isInstanceOf(DataAccessException.class);
    assertThat(transactionCount()).isOne();
    assertThat(entryCount()).isEqualTo(2);
  }

  private void markAccounted(Payment payment) {
    jdbcClient
        .sql(
            """
            UPDATE payments
            SET state = 'CAPTURE_ACCOUNTED', version = version + 1, updated_at = :updatedAt
            WHERE id = :paymentId AND state = 'CAPTURE_CONFIRMED'
            """)
        .param("updatedAt", now())
        .param("paymentId", payment.paymentId())
        .update();
  }

  private UUID insertCaptureHeader(Payment payment) {
    return jdbcClient
        .sql(
            """
            INSERT INTO ledger_transactions (
                journal_type, source_type, source_id, payment_id, order_id, currency,
                description, correlation_id, created_by, posted_at
            ) VALUES (
                'PAYMENT_CAPTURE', 'PAYMENT_CAPTURE', :paymentId, :paymentId, :orderId, 'INR',
                'Constraint test', 'constraint-test', 'ledger-test', :postedAt
            )
            RETURNING id
            """)
        .param("paymentId", payment.paymentId())
        .param("orderId", payment.orderId())
        .param("postedAt", now())
        .query(UUID.class)
        .single();
  }

  private void insertEntry(UUID transactionId, String accountCode, String side, long amountMinor) {
    jdbcClient
        .sql(
            """
            INSERT INTO ledger_entries (
                transaction_id, account_id, side, amount_minor, currency, created_at
            )
            SELECT :transactionId, id, :side, :amountMinor, 'INR', :createdAt
            FROM ledger_accounts
            WHERE code = :accountCode
            """)
        .param("transactionId", transactionId)
        .param("side", side)
        .param("amountMinor", amountMinor)
        .param("createdAt", now())
        .param("accountCode", accountCode)
        .update();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
