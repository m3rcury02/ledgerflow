package com.ledgerflow.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationEffectIdentity;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.persistence.CatalogWriteOutcome;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.NotificationProcessOutcome;
import com.ledgerflow.notifications.internal.persistence.TerminalDltRecord;
import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.testing.KafkaIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

class KafkaRetryAndDltIntegrationTest extends KafkaIntegrationTest {

  @Autowired private java.util.List<OperationRecoveryHandler> recoveryHandlers;
  @Autowired private EventEnvelopeCodec codec;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private JdbcNotificationStore notificationStore;
  @Autowired private MeterRegistry meterRegistry;

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

    OperationRecoveryResult replay =
        deadLetterHandler()
            .recover(
                new OperationRecoveryContext(
                    UUID.randomUUID(),
                    deadLetter.id(),
                    "dlt-replay-test",
                    true,
                    false,
                    Duration.ofSeconds(2),
                    () -> assertThat(true).isTrue()));

    assertThat(replay.status()).isEqualTo(OperationRecoveryResult.Status.COMPLETED);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    assertThat(inboxCount()).isOne();
    assertThat(replayStatus(deadLetter.id())).isEqualTo("REPLAYED");
    assertThat(replayAuditActions(deadLetter.id())).containsExactly("REQUESTED", "PUBLISHED");
    assertReplayAuditIsImmutable(deadLetter.id());
  }

  private OperationRecoveryHandler deadLetterHandler() {
    return recoveryHandlers.stream()
        .filter(handler -> handler.operationType() == OperationType.DEAD_LETTER)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void malformedMainMessageCreatesTerminalEvidenceWithoutStoringRawPayload() throws Exception {
    String poisonMarker = "raw-secret-poison-marker";
    ProducerRecord<String, String> record =
        new ProducerRecord<>(MAIN_TOPIC, UUID.randomUUID().toString(), "{not-json:" + poisonMarker);
    addHeader(record, "x-correlation-id", "poison-test");

    kafkaTemplate.send(record).get();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTerminalDltCount(1));
    TerminalDlt malformed =
        jdbcClient
            .sql(
                """
                SELECT dlt_topic, dlt_partition, dlt_offset, payload_size, failure_code
                FROM terminal_dlt_records
                """)
            .query(
                (resultSet, rowNumber) ->
                    new TerminalDlt(
                        resultSet.getString("dlt_topic"),
                        resultSet.getInt("dlt_partition"),
                        resultSet.getLong("dlt_offset"),
                        resultSet.getInt("payload_size"),
                        resultSet.getString("failure_code")))
            .single();
    assertThat(malformed.dltTopic()).isEqualTo(DLT_TOPIC);
    assertThat(malformed.dltPartition()).isZero();
    assertThat(malformed.dltOffset()).isNotNegative();
    assertThat(malformed.payloadSize()).isPositive();
    assertThat(malformed.failureCode()).isEqualTo("DLT_EVENT_INVALID");
    assertDltCount(0);
    assertThat(databaseContains(poisonMarker)).isFalse();
    assertTerminalEvidenceIsImmutable(malformed.dltOffset());
  }

  @Test
  void terminalEvidenceFailureRetainsOffsetThenRecoversWithoutDuplicateEvidence() throws Exception {
    double failureCount =
        metricCount("ledgerflow.notifications.dlt.terminal", "outcome", "persistence_failure");
    PaymentCapturedEventV1 validEvent = event();
    ProducerRecord<String, String> malformed =
        new ProducerRecord<>(DLT_TOPIC, validEvent.data().orderId().toString(), "missing-route");
    installRejectingTerminalEvidenceTrigger();
    try {
      kafkaTemplate.send(malformed).get();
      kafkaTemplate.send(dltRecord(validEvent, 78L)).get();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertTerminalDltCount(0);
                assertDltCount(0);
                assertThat(
                        metricCount(
                            "ledgerflow.notifications.dlt.terminal",
                            "outcome",
                            "persistence_failure"))
                    .isGreaterThan(failureCount);
              });
    } finally {
      removeRejectingTerminalEvidenceTrigger();
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertTerminalDltCount(1);
              assertDltCount(1);
            });
  }

  @Test
  void terminalEvidenceRedeliveryIsIdempotentAndConflictingEvidenceFailsClosed() {
    Instant observedAt = Instant.parse("2026-07-15T11:30:00Z");
    TerminalDltRecord evidence =
        new TerminalDltRecord(
            "ledgerflow-notifications-v1",
            DLT_TOPIC,
            0,
            910L,
            codec.hash("key"),
            3,
            codec.hash("payload"),
            7,
            "{\"b\":\"two\",\"a\":\"one\"}",
            "DLT_ORIGINAL_ROUTE_MISSING",
            "Required original-routing evidence is missing.",
            observedAt);

    assertThat(notificationStore.catalogTerminal(evidence)).isEqualTo(CatalogWriteOutcome.INSERTED);
    assertThat(
            notificationStore.catalogTerminal(
                new TerminalDltRecord(
                    evidence.consumerName(),
                    evidence.dltTopic(),
                    evidence.dltPartition(),
                    evidence.dltOffset(),
                    evidence.keyHash(),
                    evidence.keySize(),
                    evidence.payloadHash(),
                    evidence.payloadSize(),
                    "{\"a\":\"one\",\"b\":\"two\"}",
                    evidence.failureCode(),
                    evidence.failureSummary(),
                    evidence.observedAt())))
        .isEqualTo(CatalogWriteOutcome.DUPLICATE);
    assertThatThrownBy(
            () ->
                notificationStore.catalogTerminal(
                    new TerminalDltRecord(
                        evidence.consumerName(),
                        evidence.dltTopic(),
                        evidence.dltPartition(),
                        evidence.dltOffset(),
                        evidence.keyHash(),
                        evidence.keySize(),
                        codec.hash("changed"),
                        7,
                        evidence.safeHeaders(),
                        evidence.failureCode(),
                        evidence.failureSummary(),
                        evidence.observedAt())))
        .isInstanceOf(NotificationIntegrityException.class);
    assertTerminalDltCount(1);
  }

  @Test
  void reEnvelopedSemanticDuplicateCreatesOneNotification() throws Exception {
    PaymentCapturedEventV1 original = event();
    PaymentCapturedEventV1 duplicate = withEventId(original, UUID.randomUUID());

    kafkaTemplate.send(record(original)).get();
    kafkaTemplate.send(record(duplicate)).get();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertInboxCount(2));
    assertNotificationCount(1);
    assertThat(inboxOutcomes()).containsExactlyInAnyOrder("APPLIED", "SEMANTIC_DUPLICATE");
    assertThat(metricCount("ledgerflow.notifications.effects", "outcome", "semantic_duplicate"))
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void reEnvelopedSemanticConflictReachesDltWithoutAnotherSideEffect() throws Exception {
    PaymentCapturedEventV1 original = event();
    PaymentCapturedEventV1 conflict =
        new PaymentCapturedEventV1(
            UUID.randomUUID(),
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

    kafkaTemplate.send(record(original)).get();
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    kafkaTemplate.send(record(conflict)).get();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertDltCount(1));
    assertNotificationCount(1);
    assertThat(inboxCount()).isOne();
    assertThat(
            jdbcClient
                .sql("SELECT failure_code FROM dead_letter_records")
                .query(String.class)
                .single())
        .isEqualTo("NOTIFICATION_EVENT_INTEGRITY");
    assertThat(metricCount("ledgerflow.notifications.effects", "outcome", "semantic_conflict"))
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void concurrentReEnvelopingConvergesOnOneSemanticEffect() throws Exception {
    PaymentCapturedEventV1 first = event();
    PaymentCapturedEventV1 second = withEventId(first, UUID.randomUUID());
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<NotificationProcessOutcome> firstResult =
          executor.submit(() -> processDirectly(first, 101L, start));
      Future<NotificationProcessOutcome> secondResult =
          executor.submit(() -> processDirectly(second, 102L, start));
      start.countDown();

      assertThat(
              java.util.List.of(
                  firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              NotificationProcessOutcome.APPLIED, NotificationProcessOutcome.SEMANTIC_DUPLICATE);
    }

    assertNotificationCount(1);
    assertThat(inboxCount()).isEqualTo(2);
  }

  @Test
  void malformedDirectDltRecordDoesNotStarveTheNextRecord() throws Exception {
    PaymentCapturedEventV1 validEvent = event();
    ProducerRecord<String, String> malformed =
        new ProducerRecord<>(DLT_TOPIC, validEvent.data().orderId().toString(), "missing-route");
    long malformedOffset = kafkaTemplate.send(malformed).get().getRecordMetadata().offset();
    kafkaTemplate.send(dltRecord(validEvent, 77L)).get();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertTerminalDltCount(1);
              assertDltCount(1);
            });
    TerminalCoordinate coordinate = terminalCoordinate();
    assertThat(coordinate.topic()).isEqualTo(DLT_TOPIC);
    assertThat(coordinate.partition()).isZero();
    assertThat(coordinate.offset()).isEqualTo(malformedOffset);
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
    assertThat(metricCount("ledgerflow.notifications.effects", "outcome", "transport_conflict"))
        .isGreaterThanOrEqualTo(1.0);
  }

  private PaymentCapturedEventV1 event() {
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-13T15:00:00.123456789Z");
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

  private PaymentCapturedEventV1 withEventId(PaymentCapturedEventV1 event, UUID eventId) {
    return new PaymentCapturedEventV1(
        eventId,
        event.eventType(),
        event.schemaVersion(),
        event.aggregateId(),
        event.correlationId(),
        event.causationId(),
        event.occurredAt(),
        event.data());
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

  private ProducerRecord<String, String> dltRecord(
      PaymentCapturedEventV1 event, long originalOffset) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(DLT_TOPIC, event.data().orderId().toString(), codec.serialize(event));
    addIdentityHeaders(record, event);
    record
        .headers()
        .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, MAIN_TOPIC.getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add(
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
    record
        .headers()
        .add(
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            ByteBuffer.allocate(Long.BYTES).putLong(originalOffset).array());
    return record;
  }

  private void addIdentityHeaders(
      ProducerRecord<String, String> record, PaymentCapturedEventV1 event) {
    addHeader(record, "event_id", event.eventId().toString());
    addHeader(record, "event_type", event.eventType());
    addHeader(record, "schema_version", Integer.toString(event.schemaVersion()));
    addHeader(record, "aggregate_id", event.aggregateId().toString());
    addHeader(record, "causation_id", event.causationId().toString());
    addHeader(record, "x-correlation-id", event.correlationId());
  }

  private NotificationProcessOutcome processDirectly(
      PaymentCapturedEventV1 event, long offset, CountDownLatch start) throws Exception {
    assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
    String canonical = codec.serialize(event);
    return notificationStore.process(
        event,
        NotificationEffectIdentity.from(event),
        codec.hash(canonical),
        MAIN_TOPIC,
        0,
        offset,
        "concurrent-processing",
        Instant.parse("2026-07-15T11:00:00Z"));
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

  private void installRejectingTerminalEvidenceTrigger() {
    jdbcClient
        .sql(
            """
            CREATE FUNCTION reject_test_terminal_evidence_insert() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'test terminal evidence failure';
            END;
            $$
            """)
        .update();
    jdbcClient
        .sql(
            """
            CREATE TRIGGER reject_test_terminal_evidence_insert
            BEFORE INSERT ON terminal_dlt_records
            FOR EACH ROW EXECUTE FUNCTION reject_test_terminal_evidence_insert()
            """)
        .update();
  }

  private void removeRejectingTerminalEvidenceTrigger() {
    jdbcClient
        .sql("DROP TRIGGER IF EXISTS reject_test_terminal_evidence_insert ON terminal_dlt_records")
        .update();
    jdbcClient.sql("DROP FUNCTION IF EXISTS reject_test_terminal_evidence_insert()").update();
  }

  private void assertDltCount(long expected) {
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM dead_letter_records").query(Long.class).single())
        .isEqualTo(expected);
  }

  private void assertTerminalDltCount(long expected) {
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM terminal_dlt_records").query(Long.class).single())
        .isEqualTo(expected);
  }

  private void assertInboxCount(long expected) {
    assertThat(inboxCount()).isEqualTo(expected);
  }

  private java.util.List<String> inboxOutcomes() {
    return jdbcClient
        .sql("SELECT processing_outcome FROM notification_inbox ORDER BY event_id")
        .query(String.class)
        .list();
  }

  private TerminalCoordinate terminalCoordinate() {
    return jdbcClient
        .sql("SELECT dlt_topic, dlt_partition, dlt_offset FROM terminal_dlt_records")
        .query(
            (resultSet, rowNumber) ->
                new TerminalCoordinate(
                    resultSet.getString("dlt_topic"),
                    resultSet.getInt("dlt_partition"),
                    resultSet.getLong("dlt_offset")))
        .single();
  }

  private double metricCount(String name, String tagName, String tagValue) {
    return meterRegistry.get(name).tag(tagName, tagValue).counter().count();
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

  private void assertReplayAuditIsImmutable(UUID recordId) {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        UPDATE message_replay_audit SET reason = 'tampered privileged reason'
                        WHERE dead_letter_record_id = :recordId
                        """)
                    .param("recordId", recordId)
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("DELETE FROM message_replay_audit WHERE dead_letter_record_id = :recordId")
                    .param("recordId", recordId)
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThat(replayAuditActions(recordId)).containsExactly("REQUESTED", "PUBLISHED");
  }

  private void assertTerminalEvidenceIsImmutable(long dltOffset) {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        UPDATE terminal_dlt_records SET failure_summary = 'tampered evidence'
                        WHERE dlt_offset = :dltOffset
                        """)
                    .param("dltOffset", dltOffset)
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("DELETE FROM terminal_dlt_records WHERE dlt_offset = :dltOffset")
                    .param("dltOffset", dltOffset)
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertTerminalDltCount(1);
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
                UNION ALL
                SELECT 1 FROM terminal_dlt_records
                WHERE safe_headers::text LIKE :marker
                   OR failure_summary LIKE :marker
            )
            """)
        .param("marker", "%" + marker + "%")
        .query(Boolean.class)
        .single();
  }

  private record DltView(UUID id, int attemptCount, boolean replayable) {}

  private record TerminalDlt(
      String dltTopic, int dltPartition, long dltOffset, int payloadSize, String failureCode) {}

  private record TerminalCoordinate(String topic, int partition, long offset) {}
}
