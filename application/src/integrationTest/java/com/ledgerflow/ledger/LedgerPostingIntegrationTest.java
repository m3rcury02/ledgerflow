package com.ledgerflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.ledger.api.PostCorrectionCommand;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.ledger.internal.application.LedgerIntegrityException;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPostingIntegrationTest extends LedgerIntegrationTestSupport {

  @Test
  void postsOneBalancedJournalWithPaymentAndAuditMetadata() {
    Payment confirmed = confirmedPayment();
    String postingCorrelationId = correlationId();

    PostedJournal posted =
        ledgerPosting.postPaymentCapture(
            new PostPaymentCaptureCommand(
                confirmed.paymentId(), postingCorrelationId, LEDGER_ACTOR));

    assertThat(posted.paymentId()).isEqualTo(confirmed.paymentId());
    assertThat(posted.orderId()).isEqualTo(confirmed.orderId());
    assertThat(posted.debitTotalMinor()).isEqualTo(confirmed.amount().amountMinor());
    assertThat(posted.currency()).isEqualTo("INR");
    assertThat(posted.entryCount()).isEqualTo(2);
    assertThat(posted.replayed()).isFalse();
    assertThat(paymentWorkflow.get(confirmed.paymentId()).state())
        .isEqualTo(PaymentState.CAPTURE_ACCOUNTED);
    assertThat(journalLines(posted))
        .containsExactlyInAnyOrder(
            "PAYMENT_CLEARING:D:" + confirmed.amount().amountMinor(),
            "MERCHANT_PAYABLE:C:" + confirmed.amount().amountMinor());
    assertThat(auditMetadata(posted))
        .isEqualTo(
            new AuditMetadata(
                LEDGER_ACTOR, confirmed.paymentId(), postingCorrelationId, posted.postedAt()));
  }

  @Test
  void replayingAPostedPaymentReturnsTheOriginalJournal() {
    Payment confirmed = confirmedPayment();

    PostedJournal first = postCapture(confirmed);
    Payment captureReplay = paymentWorkflow.capture(confirmed.paymentId(), correlationId());
    PostedJournal replay = postCapture(confirmed);

    assertThat(captureReplay.state()).isEqualTo(PaymentState.CAPTURE_ACCOUNTED);
    assertThat(PROVIDER.callCount("CAPTURE", confirmed.captureRequestId())).isOne();
    assertThat(replay.transactionId()).isEqualTo(first.transactionId());
    assertThat(replay.postedAt()).isEqualTo(first.postedAt());
    assertThat(replay.replayed()).isTrue();
    assertThat(transactionCount()).isOne();
    assertThat(entryCount()).isEqualTo(2);
  }

  @Test
  void correctionAppendsAnExactCompensatingJournalAndIsReplayable() {
    PostedJournal original = postCapture(confirmedPayment());
    PostCorrectionCommand command =
        new PostCorrectionCommand(
            original.transactionId(),
            "Provider settlement was reversed",
            correlationId(),
            "operator:test");

    PostedJournal correction = ledgerPosting.postCorrection(command);
    PostedJournal replay = ledgerPosting.postCorrection(command);

    assertThat(correction.reversesTransactionId()).isEqualTo(original.transactionId());
    assertThat(correction.transactionId()).isNotEqualTo(original.transactionId());
    assertThat(replay.transactionId()).isEqualTo(correction.transactionId());
    assertThat(replay.replayed()).isTrue();
    assertThat(journalLines(correction))
        .containsExactlyInAnyOrder(
            "PAYMENT_CLEARING:C:" + correction.debitTotalMinor(),
            "MERCHANT_PAYABLE:D:" + correction.debitTotalMinor());
    assertThat(transactionCount()).isEqualTo(2);
    assertThat(entryCount()).isEqualTo(4);
    assertThat(accountBalance("PAYMENT_CLEARING")).isZero();
    assertThat(accountBalance("MERCHANT_PAYABLE")).isZero();
  }

  @Test
  void correctionReplayRejectsDifferentReasonMetadata() {
    PostedJournal original = postCapture(confirmedPayment());
    ledgerPosting.postCorrection(
        new PostCorrectionCommand(
            original.transactionId(),
            "Provider settlement was reversed",
            correlationId(),
            "operator:test"));

    assertThatThrownBy(
            () ->
                ledgerPosting.postCorrection(
                    new PostCorrectionCommand(
                        original.transactionId(),
                        "A materially different correction reason",
                        correlationId(),
                        "operator:test")))
        .isInstanceOf(LedgerIntegrityException.class);
    assertThat(transactionCount()).isEqualTo(2);
  }

  private List<String> journalLines(PostedJournal journal) {
    return jdbcClient
        .sql(
            """
            SELECT a.code || ':' || e.side || ':' || e.amount_minor
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE e.transaction_id = :transactionId
            """)
        .param("transactionId", journal.transactionId())
        .query(String.class)
        .list();
  }

  private AuditMetadata auditMetadata(PostedJournal journal) {
    return jdbcClient
        .sql(
            """
            SELECT created_by, source_id, correlation_id, posted_at
            FROM ledger_transactions
            WHERE id = :transactionId
            """)
        .param("transactionId", journal.transactionId())
        .query(
            (resultSet, rowNumber) ->
                new AuditMetadata(
                    resultSet.getString("created_by"),
                    resultSet.getObject("source_id", UUID.class),
                    resultSet.getString("correlation_id"),
                    resultSet.getTimestamp("posted_at").toInstant()))
        .single();
  }

  private long accountBalance(String accountCode) {
    return jdbcClient
        .sql(
            """
            SELECT COALESCE(sum(
                CASE e.side WHEN 'D' THEN e.amount_minor ELSE -e.amount_minor END
            ), 0)
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE a.code = :accountCode
            """)
        .param("accountCode", accountCode)
        .query(Long.class)
        .single();
  }

  private record AuditMetadata(
      String actor, UUID sourceId, String correlationId, Instant postedAt) {}
}
