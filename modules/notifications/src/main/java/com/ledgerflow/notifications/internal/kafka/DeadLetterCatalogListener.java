package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationSemanticConflictException;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.CatalogWriteOutcome;
import com.ledgerflow.notifications.internal.persistence.DeadLetterCatalogEntry;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.TerminalDltRecord;
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
  private final DeadLetterInputClassifier inputClassifier;
  private final EventEnvelopeCodec codec;
  private final JdbcNotificationStore store;
  private final NotificationsProperties properties;
  private final ObjectMapper objectMapper;
  private final NotificationMetrics metrics;
  private final Clock clock;

  DeadLetterCatalogListener(
      NotificationEventValidator validator,
      DeadLetterInputClassifier inputClassifier,
      EventEnvelopeCodec codec,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      ObjectMapper objectMapper,
      NotificationMetrics metrics,
      Clock clock) {
    this.validator = validator;
    this.inputClassifier = inputClassifier;
    this.codec = codec;
    this.store = store;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.clock = clock;
  }

  @KafkaListener(
      id = "ledgerflow-notification-dlt-catalog",
      topics = "${ledgerflow.notifications.dead-letter-topic:ledgerflow.payment-captured.v1.dlt}",
      groupId = "${ledgerflow.notifications.dead-letter-group-id:ledgerflow-notifications-dlt-v1}",
      containerFactory = "notificationDltKafkaListenerContainerFactory")
  void catalog(ConsumerRecord<String, String> record) {
    byte[] rawKey =
        record.key() == null ? new byte[0] : record.key().getBytes(StandardCharsets.UTF_8);
    byte[] rawPayload =
        record.value() == null ? new byte[0] : record.value().getBytes(StandardCharsets.UTF_8);
    DeadLetterInputClassifier.RoutingResult routing =
        inputClassifier.routing(record.headers(), properties.topic());
    if (!routing.valid()) {
      catalogTerminal(record, rawKey, rawPayload, routing.failure());
      return;
    }
    if (rawPayload.length == 0) {
      catalogTerminal(
          record, rawKey, rawPayload, DeadLetterInputClassifier.TerminalFailure.PAYLOAD_EMPTY);
      return;
    }
    if (rawPayload.length > NotificationEventValidator.MAX_PAYLOAD_SIZE) {
      catalogTerminal(
          record, rawKey, rawPayload, DeadLetterInputClassifier.TerminalFailure.PAYLOAD_TOO_LARGE);
      return;
    }

    ValidatedNotificationEvent validated;
    try {
      validated = validator.validate(record.key(), record.value(), record.headers());
    } catch (NotificationValidationException exception) {
      catalogTerminal(
          record, rawKey, rawPayload, DeadLetterInputClassifier.TerminalFailure.EVENT_INVALID);
      return;
    }

    Failure failure = classifyFailure(record.headers());
    boolean replayable = !failure.integrityFailure();
    Instant now = clock.instant();
    DeadLetterInputClassifier.OriginalCoordinates coordinates = routing.coordinates();
    store.catalog(
        new DeadLetterCatalogEntry(
            validated.event().eventId(),
            properties.consumerName(),
            coordinates.topic(),
            coordinates.partition(),
            coordinates.offset(),
            validated.eventKey(),
            validated.canonicalPayload(),
            codec.hash(record.value() == null ? "" : record.value()),
            rawPayload.length,
            safeHeaders(record.headers()),
            failure.code(),
            failure.summary(),
            KafkaEventHeaders.deliveryAttempt(record.headers()),
            replayable,
            now));
  }

  private void catalogTerminal(
      ConsumerRecord<String, String> record,
      byte[] rawKey,
      byte[] rawPayload,
      DeadLetterInputClassifier.TerminalFailure failure) {
    try {
      CatalogWriteOutcome outcome =
          store.catalogTerminal(
              new TerminalDltRecord(
                  properties.consumerName(),
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  codec.hash(record.key() == null ? "" : record.key()),
                  rawKey.length,
                  codec.hash(record.value() == null ? "" : record.value()),
                  rawPayload.length,
                  safeHeaders(record.headers()),
                  failure.code(),
                  failure.summary(),
                  clock.instant()));
      metrics.terminal(
          outcome == CatalogWriteOutcome.INSERTED
              ? NotificationMetrics.TerminalMetric.RECORDED
              : NotificationMetrics.TerminalMetric.DUPLICATE);
    } catch (RuntimeException exception) {
      metrics.terminal(NotificationMetrics.TerminalMetric.PERSISTENCE_FAILURE);
      throw exception;
    }
  }

  private Failure classifyFailure(Headers headers) {
    String exceptionClass =
        KafkaEventHeaders.optionalLastText(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    if (exceptionClass == null) {
      exceptionClass = KafkaEventHeaders.optionalLastText(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }
    if (NotificationIntegrityException.class.getName().equals(exceptionClass)
        || NotificationSemanticConflictException.class.getName().equals(exceptionClass)) {
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
        KafkaEventHeaders.optionalSingleText(headers, KafkaEventHeaders.CORRELATION_ID),
        CORRELATION_PATTERN,
        64);
    addIfValid(
        safe,
        KafkaEventHeaders.TRACEPARENT,
        KafkaEventHeaders.optionalSingleText(headers, KafkaEventHeaders.TRACEPARENT),
        TRACEPARENT_PATTERN,
        55);
    addIfValid(
        safe,
        KafkaEventHeaders.TRACESTATE,
        KafkaEventHeaders.optionalSingleText(headers, KafkaEventHeaders.TRACESTATE),
        "[^\\p{Cntrl}]+",
        512);
    addIfValid(
        safe,
        KafkaEventHeaders.REPLAY_REQUEST_ID,
        KafkaEventHeaders.optionalSingleText(headers, KafkaEventHeaders.REPLAY_REQUEST_ID),
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
