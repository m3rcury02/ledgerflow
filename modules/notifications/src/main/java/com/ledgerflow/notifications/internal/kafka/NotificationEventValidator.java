package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public final class NotificationEventValidator {

  static final int MAX_PAYLOAD_SIZE = 1_048_576;
  private static final String CORRELATION_PATTERN = "[A-Za-z0-9._-]{1,64}";

  private final EventEnvelopeCodec codec;

  public NotificationEventValidator(EventEnvelopeCodec codec) {
    this.codec = codec;
  }

  public ValidatedNotificationEvent validateMain(
      ConsumerRecord<String, String> record, String expectedTopic) {
    if (!expectedTopic.equals(record.topic())) {
      throw new NotificationValidationException("Kafka source topic is not supported");
    }
    return validate(record.key(), record.value(), record.headers());
  }

  public ValidatedNotificationEvent validateStored(String eventKey, String payload) {
    PaymentCapturedEventV1 event = deserialize(payload);
    validateEvent(event, eventKey);
    String canonicalPayload = codec.serialize(event);
    return new ValidatedNotificationEvent(
        event, eventKey, canonicalPayload, codec.hash(canonicalPayload), event.correlationId());
  }

  ValidatedNotificationEvent validate(
      String eventKey, String payload, org.apache.kafka.common.header.Headers headers) {
    try {
      int payloadSize = payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
      if (payload == null || payloadSize == 0 || payloadSize > MAX_PAYLOAD_SIZE) {
        throw new IllegalArgumentException("Kafka payload size is invalid");
      }
      PaymentCapturedEventV1 event = deserialize(payload);
      validateEvent(event, eventKey);
      validateIdentityHeaders(event, headers);
      String processingCorrelation =
          KafkaEventHeaders.requireSingleText(headers, KafkaEventHeaders.CORRELATION_ID);
      if (!processingCorrelation.matches(CORRELATION_PATTERN)) {
        throw new IllegalArgumentException("Kafka correlation header is invalid");
      }
      String canonicalPayload = codec.serialize(event);
      return new ValidatedNotificationEvent(
          event, eventKey, canonicalPayload, codec.hash(canonicalPayload), processingCorrelation);
    } catch (NotificationValidationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new NotificationValidationException(
          "Payment-captured Kafka record is invalid", exception);
    }
  }

  private PaymentCapturedEventV1 deserialize(String payload) {
    try {
      return codec.deserialize(payload);
    } catch (IllegalArgumentException exception) {
      throw new NotificationValidationException("Payment-captured payload is invalid", exception);
    }
  }

  private void validateEvent(PaymentCapturedEventV1 event, String eventKey) {
    PaymentCapturedDataV1 data = event.data();
    if (eventKey == null
        || data.orderId() == null
        || data.paymentId() == null
        || data.ledgerTransactionId() == null
        || data.capturedAt() == null
        || !event.aggregateId().equals(data.paymentId())
        || !event.occurredAt().equals(data.capturedAt())
        || !data.orderId().toString().equals(eventKey)) {
      throw new NotificationValidationException("Payment-captured event data or key is invalid");
    }
    try {
      UUID.fromString(eventKey);
    } catch (IllegalArgumentException exception) {
      throw new NotificationValidationException("Payment-captured event key is invalid", exception);
    }
  }

  private void validateIdentityHeaders(
      PaymentCapturedEventV1 event, org.apache.kafka.common.header.Headers headers) {
    requireEquals(event.eventId().toString(), headers, KafkaEventHeaders.EVENT_ID);
    requireEquals(event.eventType(), headers, KafkaEventHeaders.EVENT_TYPE);
    requireEquals(
        Integer.toString(event.schemaVersion()), headers, KafkaEventHeaders.SCHEMA_VERSION);
    requireEquals(event.aggregateId().toString(), headers, KafkaEventHeaders.AGGREGATE_ID);
    requireEquals(event.causationId().toString(), headers, KafkaEventHeaders.CAUSATION_ID);
  }

  private void requireEquals(
      String expected, org.apache.kafka.common.header.Headers headers, String name) {
    if (!expected.equals(KafkaEventHeaders.requireSingleText(headers, name))) {
      throw new IllegalArgumentException("Kafka identity header does not match the event");
    }
  }
}
