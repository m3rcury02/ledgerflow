package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.DeadLetterCatalogEntry;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class DeadLetterCatalogListener {

  private static final String CORRELATION_PATTERN = "[A-Za-z0-9._-]{1,64}";
  private static final String TRACEPARENT_PATTERN = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

  private final NotificationEventValidator validator;
  private final EventEnvelopeCodec codec;
  private final JdbcNotificationStore store;
  private final NotificationsProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  DeadLetterCatalogListener(
      NotificationEventValidator validator,
      EventEnvelopeCodec codec,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.validator = validator;
    this.codec = codec;
    this.store = store;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @KafkaListener(
      id = "ledgerflow-notification-dlt-catalog",
      topics = "${ledgerflow.notifications.dead-letter-topic:ledgerflow.payment-captured.v1.dlt}",
      groupId = "${ledgerflow.notifications.dead-letter-group-id:ledgerflow-notifications-dlt-v1}",
      containerFactory = "notificationDltKafkaListenerContainerFactory")
  void catalog(ConsumerRecord<String, String> record) {
    String originalTopic = KafkaEventHeaders.originalTopic(record.headers());
    int originalPartition = KafkaEventHeaders.originalPartition(record.headers());
    long originalOffset = KafkaEventHeaders.originalOffset(record.headers());
    if (!properties.topic().equals(originalTopic)) {
      throw new NotificationValidationException("Dead-letter original topic is not supported");
    }

    byte[] rawPayload =
        record.value() == null ? new byte[0] : record.value().getBytes(StandardCharsets.UTF_8);
    if (rawPayload.length > NotificationEventValidator.MAX_PAYLOAD_SIZE) {
      throw new NotificationValidationException("Dead-letter payload exceeds the catalog limit");
    }

    ValidatedNotificationEvent validated = null;
    try {
      validated = validator.validate(record.key(), record.value(), record.headers());
    } catch (NotificationValidationException ignored) {
      // Malformed input is intentionally cataloged without its raw body or parsed identity.
    }

    Failure failure = classifyFailure(record.headers());
    boolean replayable = validated != null && !failure.integrityFailure();
    Instant now = clock.instant();
    store.catalog(
        new DeadLetterCatalogEntry(
            validated == null ? null : validated.event().eventId(),
            properties.consumerName(),
            originalTopic,
            originalPartition,
            originalOffset,
            validated == null ? null : validated.eventKey(),
            validated == null ? null : validated.canonicalPayload(),
            codec.hash(record.value() == null ? "" : record.value()),
            rawPayload.length,
            safeHeaders(record.headers()),
            failure.code(),
            failure.summary(),
            KafkaEventHeaders.deliveryAttempt(record.headers()),
            replayable,
            now));
  }

  private Failure classifyFailure(Headers headers) {
    String exceptionClass =
        KafkaEventHeaders.optionalLastText(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    if (exceptionClass == null) {
      exceptionClass = KafkaEventHeaders.optionalLastText(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }
    if (NotificationIntegrityException.class.getName().equals(exceptionClass)) {
      return new Failure(
          "NOTIFICATION_EVENT_INTEGRITY",
          "The event identity conflicts with previously processed content.",
          true);
    }
    if (NotificationValidationException.class.getName().equals(exceptionClass)) {
      return new Failure(
          "NOTIFICATION_EVENT_INVALID",
          "The event contract, key, or identity headers are invalid.",
          false);
    }
    return new Failure(
        "NOTIFICATION_RETRIES_EXHAUSTED",
        "Notification processing exhausted its bounded retry attempts.",
        false);
  }

  private String safeHeaders(Headers headers) {
    Map<String, String> safe = new LinkedHashMap<>();
    addIfValid(
        safe,
        KafkaEventHeaders.CORRELATION_ID,
        KafkaEventHeaders.optionalLastText(headers, KafkaEventHeaders.CORRELATION_ID),
        CORRELATION_PATTERN,
        64);
    addIfValid(
        safe,
        KafkaEventHeaders.TRACEPARENT,
        KafkaEventHeaders.optionalLastText(headers, KafkaEventHeaders.TRACEPARENT),
        TRACEPARENT_PATTERN,
        55);
    addIfValid(
        safe,
        KafkaEventHeaders.TRACESTATE,
        KafkaEventHeaders.optionalLastText(headers, KafkaEventHeaders.TRACESTATE),
        "[^\\p{Cntrl}]+",
        512);
    addIfValid(
        safe,
        KafkaEventHeaders.REPLAY_REQUEST_ID,
        KafkaEventHeaders.optionalLastText(headers, KafkaEventHeaders.REPLAY_REQUEST_ID),
        "[0-9a-f-]{36}",
        36);
    try {
      return objectMapper.writeValueAsString(safe);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Safe dead-letter headers could not be serialized", exception);
    }
  }

  private void addIfValid(
      Map<String, String> safe, String name, String value, String pattern, int maximumLength) {
    if (value != null && value.length() <= maximumLength && value.matches(pattern)) {
      safe.put(name, value);
    }
  }

  private record Failure(String code, String summary, boolean integrityFailure) {}
}
