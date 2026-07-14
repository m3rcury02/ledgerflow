package com.ledgerflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransactionalOutboxIntegrationTest extends LedgerIntegrationTestSupport {

  @Autowired private EventEnvelopeCodec codec;

  @Test
  void captureAccountingAndVersionedOutboxCommitTogetherAndReplayOnce() {
    Payment confirmed = confirmedPayment();

    PostedJournal journal = postCapture(confirmed);
    PostedJournal replay = postCapture(confirmed);

    StoredOutbox stored = loadOutbox(confirmed.paymentId());
    PaymentCapturedEventV1 envelope = codec.deserialize(stored.payload());
    assertThat(stored.status()).isEqualTo("PENDING");
    assertThat(stored.payloadHash()).isEqualTo(codec.hash(codec.serialize(envelope)));
    assertThat(envelope.eventType()).isEqualTo(PaymentCapturedEventV1.TYPE);
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    assertThat(envelope.aggregateId()).isEqualTo(confirmed.paymentId());
    assertThat(envelope.causationId()).isEqualTo(confirmed.captureRequestId());
    assertThat(envelope.correlationId()).isNotBlank();
    assertThat(envelope.occurredAt()).isEqualTo(journal.postedAt());
    assertThat(envelope.data().ledgerTransactionId()).isEqualTo(journal.transactionId());
    assertThat(replay.transactionId()).isEqualTo(journal.transactionId());
    assertThat(outboxCount()).isOne();
  }

  @Test
  void outboxInsertFailureRollsBackPaymentAndBalancedJournal() {
    Payment confirmed = confirmedPayment();
    installRejectingTrigger();
    try {
      assertThatThrownBy(() -> postCapture(confirmed)).hasMessageContaining("test outbox failure");
    } finally {
      removeRejectingTrigger();
    }

    assertThat(paymentWorkflow.get(confirmed.paymentId()).state())
        .isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(transactionCount()).isZero();
    assertThat(entryCount()).isZero();
    assertThat(outboxCount()).isZero();
  }

  private StoredOutbox loadOutbox(UUID paymentId) {
    return jdbcClient
        .sql(
            """
            SELECT status, payload::text AS payload, payload_hash
            FROM outbox_events
            WHERE aggregate_id = :paymentId
            """)
        .param("paymentId", paymentId)
        .query(
            (resultSet, rowNumber) ->
                new StoredOutbox(
                    resultSet.getString("status"),
                    resultSet.getString("payload"),
                    resultSet.getBytes("payload_hash")))
        .single();
  }

  private long outboxCount() {
    return jdbcClient.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
  }

  private void installRejectingTrigger() {
    jdbcClient
        .sql(
            """
            CREATE FUNCTION reject_test_outbox_insert() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'test outbox failure';
            END;
            $$
            """)
        .update();
    jdbcClient
        .sql(
            """
            CREATE TRIGGER reject_test_outbox_insert
            BEFORE INSERT ON outbox_events
            FOR EACH ROW EXECUTE FUNCTION reject_test_outbox_insert()
            """)
        .update();
  }

  private void removeRejectingTrigger() {
    jdbcClient.sql("DROP TRIGGER IF EXISTS reject_test_outbox_insert ON outbox_events").update();
    jdbcClient.sql("DROP FUNCTION IF EXISTS reject_test_outbox_insert()").update();
  }

  private record StoredOutbox(String status, String payload, byte[] payloadHash) {}
}
