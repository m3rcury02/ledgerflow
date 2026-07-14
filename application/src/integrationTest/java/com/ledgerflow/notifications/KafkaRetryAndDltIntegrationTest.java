package com.ledgerflow.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.api.DeadLetterReplay;
import com.ledgerflow.notifications.api.ReplayOutcome;
import com.ledgerflow.notifications.api.ReplayResult;
import com.ledgerflow.testing.KafkaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaRetryAndDltIntegrationTest extends KafkaIntegrationTest {

  @Autowired private DeadLetterReplay replayService;
  @Autowired private EventEnvelopeCodec codec;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Test
  void retriesTransientFailureThreeTimesThenCatalogsAndAuditsSafeReplay() throws Exception {
    PaymentCapturedEventV1 event = event();
    ProducerRecord<String, String> record = record(event);
    installRejectingNotificationTrigger();
    try {
      kafkaTemplate.send(record).get();
      await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertDltCount(1));
    } finally {
      removeRejectingNotificationTrigger();
    }

    DltView deadLetter = replayableDeadLetter(event.eventId());
    assertThat(deadLetter.attemptCount()).isEqualTo(4);
    assertThat(deadLetter.replayable()).isTrue();
    assertThat(notificationCount()).isZero();
    assertThat(inboxCount()).isZero();

    ReplayResult replay =
        replayService.replay(
            deadLetter.id(), "operator:test", "Retry after the transient database fault cleared");

    assertThat(replay.outcome()).isEqualTo(ReplayOutcome.PUBLISHED);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    assertThat(inboxCount()).isOne();
    assertThat(replayStatus(deadLetter.id())).isEqualTo("REPLAYED");
    assertThat(replayAuditActions(deadLetter.id())).containsExactly("REQUESTED", "PUBLISHED");
  }

  @Test
  void malformedPoisonMessageGoesDirectlyToDltWithoutStoringRawPayloadOrAllowingReplay()
      throws Exception {
    String poisonMarker = "raw-secret-poison-marker";
    ProducerRecord<String, String> record =
        new ProducerRecord<>(MAIN_TOPIC, UUID.randomUUID().toString(), "{not-json:" + poisonMarker);
    addHeader(record, "x-correlation-id", "poison-test");

    kafkaTemplate.send(record).get();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertDltCount(1));
    MalformedDlt malformed =
        jdbcClient
            .sql(
                """
                SELECT event_id, event_key, validated_payload::text AS validated_payload,
                       replayable, payload_size
                FROM dead_letter_records
                """)
            .query(
                (resultSet, rowNumber) ->
                    new MalformedDlt(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_key"),
                        resultSet.getString("validated_payload"),
                        resultSet.getBoolean("replayable"),
                        resultSet.getInt("payload_size")))
            .single();
    assertThat(malformed.eventId()).isNull();
    assertThat(malformed.eventKey()).isNull();
    assertThat(malformed.validatedPayload()).isNull();
    assertThat(malformed.replayable()).isFalse();
    assertThat(malformed.payloadSize()).isPositive();
    assertThat(databaseContains(poisonMarker)).isFalse();
  }

  @Test
  void duplicateEventIdWithChangedContentCannotCreateAnotherSideEffect() throws Exception {
    PaymentCapturedEventV1 original = event();
    kafkaTemplate.send(record(original)).get();
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    PaymentCapturedEventV1 changed =
        new PaymentCapturedEventV1(
            original.eventId(),
            original.eventType(),
            original.schemaVersion(),
            original.aggregateId(),
            original.correlationId(),
            original.causationId(),
            original.occurredAt(),
            new PaymentCapturedDataV1(
                original.data().orderId(),
                original.data().paymentId(),
                original.data().ledgerTransactionId(),
                original.data().amountMinor() + 1,
                original.data().currency(),
                original.data().capturedAt()));

    kafkaTemplate.send(record(changed)).get();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertDltCount(1));
    assertNotificationCount(1);
    assertThat(inboxCount()).isOne();
    assertThat(
            jdbcClient
                .sql("SELECT replayable FROM dead_letter_records")
                .query(Boolean.class)
                .single())
        .isFalse();
    assertThat(
            jdbcClient
                .sql("SELECT failure_code FROM dead_letter_records")
                .query(String.class)
                .single())
        .isEqualTo("NOTIFICATION_EVENT_INTEGRITY");
  }

  private PaymentCapturedEventV1 event() {
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-13T15:00:00Z");
    return new PaymentCapturedEventV1(
        UUID.randomUUID(),
        PaymentCapturedEventV1.TYPE,
        PaymentCapturedEventV1.SCHEMA_VERSION,
        paymentId,
        "retry-flow",
        UUID.randomUUID(),
        now,
        new PaymentCapturedDataV1(orderId, paymentId, UUID.randomUUID(), 2599, "INR", now));
  }

  private ProducerRecord<String, String> record(PaymentCapturedEventV1 event) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(MAIN_TOPIC, event.data().orderId().toString(), codec.serialize(event));
    addHeader(record, "event_id", event.eventId().toString());
    addHeader(record, "event_type", event.eventType());
    addHeader(record, "schema_version", Integer.toString(event.schemaVersion()));
    addHeader(record, "aggregate_id", event.aggregateId().toString());
    addHeader(record, "causation_id", event.causationId().toString());
    addHeader(record, "x-correlation-id", event.correlationId());
    return record;
  }

  private void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private void installRejectingNotificationTrigger() {
    jdbcClient
        .sql(
            """
            CREATE FUNCTION reject_test_notification_insert() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'test notification failure';
            END;
            $$
            """)
        .update();
    jdbcClient
        .sql(
            """
            CREATE TRIGGER reject_test_notification_insert
            BEFORE INSERT ON notifications
            FOR EACH ROW EXECUTE FUNCTION reject_test_notification_insert()
            """)
        .update();
  }

  private void removeRejectingNotificationTrigger() {
    jdbcClient
        .sql("DROP TRIGGER IF EXISTS reject_test_notification_insert ON notifications")
        .update();
    jdbcClient.sql("DROP FUNCTION IF EXISTS reject_test_notification_insert()").update();
  }

  private void assertDltCount(long expected) {
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM dead_letter_records").query(Long.class).single())
        .isEqualTo(expected);
  }

  private DltView replayableDeadLetter(UUID eventId) {
    return jdbcClient
        .sql(
            """
            SELECT id, attempt_count, replayable
            FROM dead_letter_records WHERE event_id = :eventId
            """)
        .param("eventId", eventId)
        .query(
            (resultSet, rowNumber) ->
                new DltView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getInt("attempt_count"),
                    resultSet.getBoolean("replayable")))
        .single();
  }

  private long notificationCount() {
    return jdbcClient.sql("SELECT count(*) FROM notifications").query(Long.class).single();
  }

  private void assertNotificationCount(long expected) {
    assertThat(notificationCount()).isEqualTo(expected);
  }

  private long inboxCount() {
    return jdbcClient.sql("SELECT count(*) FROM notification_inbox").query(Long.class).single();
  }

  private String replayStatus(UUID recordId) {
    return jdbcClient
        .sql("SELECT status FROM dead_letter_records WHERE id = :recordId")
        .param("recordId", recordId)
        .query(String.class)
        .single();
  }

  private java.util.List<String> replayAuditActions(UUID recordId) {
    return jdbcClient
        .sql(
            """
            SELECT action FROM message_replay_audit
            WHERE dead_letter_record_id = :recordId
            ORDER BY occurred_at, id
            """)
        .param("recordId", recordId)
        .query(String.class)
        .list();
  }

  private boolean databaseContains(String marker) {
    return jdbcClient
        .sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM dead_letter_records
                WHERE COALESCE(validated_payload::text, '') LIKE :marker
                   OR safe_headers::text LIKE :marker
                   OR failure_summary LIKE :marker
            )
            """)
        .param("marker", "%" + marker + "%")
        .query(Boolean.class)
        .single();
  }

  private record DltView(UUID id, int attemptCount, boolean replayable) {}

  private record MalformedDlt(
      UUID eventId,
      String eventKey,
      String validatedPayload,
      boolean replayable,
      int payloadSize) {}
}
