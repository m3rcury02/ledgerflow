package com.ledgerflow.notifications.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationEventValidatorTest {

  private static final String TOPIC = "ledgerflow.payment-captured.v1";
  private static final UUID EVENT_ID = UUID.fromString("01980d4f-7b4b-7000-8000-000000000001");
  private static final UUID PAYMENT_ID = UUID.fromString("01980d4f-7b4b-7000-8000-000000000002");
  private static final UUID CAUSATION_ID = UUID.fromString("01980d4f-7b4b-7000-8000-000000000003");
  private static final UUID ORDER_ID = UUID.fromString("01980d4f-7b4b-7000-8000-000000000004");
  private static final UUID LEDGER_ID = UUID.fromString("01980d4f-7b4b-7000-8000-000000000005");

  private final EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());
  private final NotificationEventValidator validator = new NotificationEventValidator(codec);

  @Test
  void hashesTheCanonicalEnvelopeRatherThanTransportFormatting() {
    PaymentCapturedEventV1 event = event();
    String canonical = codec.serialize(event);
    ConsumerRecord<String, String> canonicalRecord = record(canonical, event);
    ConsumerRecord<String, String> formattedRecord = record("  " + canonical + "\n", event);

    ValidatedNotificationEvent first = validator.validateMain(canonicalRecord, TOPIC);
    ValidatedNotificationEvent second = validator.validateMain(formattedRecord, TOPIC);

    assertThat(first.canonicalPayload()).isEqualTo(canonical);
    assertThat(second.canonicalPayload()).isEqualTo(canonical);
    assertThat(second.canonicalPayloadHash()).containsExactly(first.canonicalPayloadHash());
  }

  @Test
  void rejectsAKeyThatDoesNotMatchTheOrder() {
    PaymentCapturedEventV1 event = event();
    ConsumerRecord<String, String> record = record(codec.serialize(event), event);
    ConsumerRecord<String, String> wrongKey =
        new ConsumerRecord<>(TOPIC, 0, 1L, UUID.randomUUID().toString(), record.value());
    record.headers().forEach(wrongKey.headers()::add);

    assertThatThrownBy(() -> validator.validateMain(wrongKey, TOPIC))
        .isInstanceOf(NotificationValidationException.class);
  }

  @Test
  void rejectsAnIdentityHeaderThatDoesNotMatchTheEnvelope() {
    PaymentCapturedEventV1 event = event();
    ConsumerRecord<String, String> record = record(codec.serialize(event), event);
    record.headers().remove(KafkaEventHeaders.EVENT_ID);
    KafkaEventHeaders.addText(
        record.headers(), KafkaEventHeaders.EVENT_ID, UUID.randomUUID().toString());

    assertThatThrownBy(() -> validator.validateMain(record, TOPIC))
        .isInstanceOf(NotificationValidationException.class);
  }

  private ConsumerRecord<String, String> record(String payload, PaymentCapturedEventV1 event) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(TOPIC, 0, 1L, ORDER_ID.toString(), payload);
    KafkaEventHeaders.addText(
        record.headers(), KafkaEventHeaders.EVENT_ID, event.eventId().toString());
    KafkaEventHeaders.addText(record.headers(), KafkaEventHeaders.EVENT_TYPE, event.eventType());
    KafkaEventHeaders.addText(
        record.headers(),
        KafkaEventHeaders.SCHEMA_VERSION,
        Integer.toString(event.schemaVersion()));
    KafkaEventHeaders.addText(
        record.headers(), KafkaEventHeaders.AGGREGATE_ID, event.aggregateId().toString());
    KafkaEventHeaders.addText(
        record.headers(), KafkaEventHeaders.CAUSATION_ID, event.causationId().toString());
    KafkaEventHeaders.addText(record.headers(), KafkaEventHeaders.CORRELATION_ID, "processing-123");
    return record;
  }

  private PaymentCapturedEventV1 event() {
    Instant occurredAt = Instant.parse("2026-07-13T15:00:00Z");
    return new PaymentCapturedEventV1(
        EVENT_ID,
        PaymentCapturedEventV1.TYPE,
        PaymentCapturedEventV1.SCHEMA_VERSION,
        PAYMENT_ID,
        "business-123",
        CAUSATION_ID,
        occurredAt,
        new PaymentCapturedDataV1(ORDER_ID, PAYMENT_ID, LEDGER_ID, 2599, "INR", occurredAt));
  }
}
