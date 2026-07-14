package com.ledgerflow.notifications.internal.application;

import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.api.DeadLetterReplay;
import com.ledgerflow.notifications.api.ReplayOutcome;
import com.ledgerflow.notifications.api.ReplayResult;
import com.ledgerflow.notifications.internal.kafka.NotificationEventValidator;
import com.ledgerflow.notifications.internal.kafka.ValidatedNotificationEvent;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.ReplayClaim;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;

public class DeadLetterReplayService implements DeadLetterReplay {

  private static final String ACTOR_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}";
  private static final String FAILURE_SUMMARY =
      "Replay publication did not receive a broker acknowledgement.";
  private static final TextMapSetter<Headers> KAFKA_HEADER_SETTER =
      (headers, name, value) -> {
        if (headers != null) {
          headers.remove(name);
          headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
      };

  private final JdbcNotificationStore store;
  private final NotificationEventValidator validator;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final NotificationsProperties properties;
  private final OpenTelemetry openTelemetry;
  private final Clock clock;

  public DeadLetterReplayService(
      JdbcNotificationStore store,
      NotificationEventValidator validator,
      KafkaTemplate<String, String> kafkaTemplate,
      NotificationsProperties properties,
      OpenTelemetry openTelemetry,
      Clock clock) {
    this.store = store;
    this.validator = validator;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
    this.openTelemetry = openTelemetry;
    this.clock = clock;
  }

  @Override
  public ReplayResult replay(UUID deadLetterRecordId, String actor, String reason) {
    validateRequest(deadLetterRecordId, actor, reason);
    UUID replayRequestId = UUID.randomUUID();
    String correlationId = "replay-" + UUID.randomUUID();
    String leaseOwner = "replay:" + UUID.randomUUID();
    ReplayClaim claim =
        store.claimReplay(
            deadLetterRecordId,
            replayRequestId,
            leaseOwner,
            actor,
            reason,
            correlationId,
            clock.instant(),
            properties.replayLeaseDuration());

    ValidatedNotificationEvent validated;
    try {
      validated = validator.validateStored(claim.eventKey(), claim.validatedPayload());
      if (!claim.eventId().equals(validated.event().eventId())) {
        throw new NotificationValidationException(
            "Catalog event identity does not match its validated payload");
      }
    } catch (RuntimeException exception) {
      store.markReplayFailed(
          claim,
          "REPLAY_PAYLOAD_INVALID",
          "Cataloged replay payload failed validation.",
          clock.instant());
      return new ReplayResult(replayRequestId, ReplayOutcome.FAILED, correlationId);
    }

    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            properties.topic(), validated.eventKey(), validated.canonicalPayload());
    addFreshHeaders(record, validated.event(), replayRequestId, correlationId);
    try (ReplayPublishSpan span = startPublishSpan(validated.event(), record.headers())) {
      try {
        kafkaTemplate
            .send(record)
            .get(properties.brokerAcknowledgementTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        span.failed(exception);
        store.markReplayFailed(claim, "REPLAY_INTERRUPTED", FAILURE_SUMMARY, clock.instant());
        return new ReplayResult(replayRequestId, ReplayOutcome.FAILED, correlationId);
      } catch (ExecutionException | TimeoutException exception) {
        span.failed(exception);
        store.markReplayFailed(claim, "REPLAY_PUBLISH_FAILED", FAILURE_SUMMARY, clock.instant());
        return new ReplayResult(replayRequestId, ReplayOutcome.FAILED, correlationId);
      } catch (RuntimeException exception) {
        span.failed(exception);
        store.markReplayFailed(claim, "REPLAY_PUBLISH_FAILED", FAILURE_SUMMARY, clock.instant());
        return new ReplayResult(replayRequestId, ReplayOutcome.FAILED, correlationId);
      }
    }

    store.markReplayPublished(claim, clock.instant());
    return new ReplayResult(replayRequestId, ReplayOutcome.PUBLISHED, correlationId);
  }

  private void validateRequest(UUID deadLetterRecordId, String actor, String reason) {
    if (deadLetterRecordId == null) {
      throw new IllegalArgumentException("deadLetterRecordId must not be null");
    }
    if (actor == null || !actor.matches(ACTOR_PATTERN)) {
      throw new IllegalArgumentException("actor has an invalid format");
    }
    if (reason == null
        || reason.length() < 10
        || reason.length() > 500
        || reason.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("reason must contain 10 to 500 safe characters");
    }
  }

  private void addFreshHeaders(
      ProducerRecord<String, String> record,
      PaymentCapturedEventV1 event,
      UUID replayRequestId,
      String correlationId) {
    addHeader(record, "event_id", event.eventId().toString());
    addHeader(record, "event_type", event.eventType());
    addHeader(record, "schema_version", Integer.toString(event.schemaVersion()));
    addHeader(record, "aggregate_id", event.aggregateId().toString());
    addHeader(record, "causation_id", event.causationId().toString());
    addHeader(record, "x-correlation-id", correlationId);
    addHeader(record, "replay_request_id", replayRequestId.toString());
    addHeader(record, "replay_causation_id", replayRequestId.toString());
  }

  private void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private ReplayPublishSpan startPublishSpan(PaymentCapturedEventV1 event, Headers headers) {
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.notifications")
            .spanBuilder("dead letter replay " + event.eventType())
            .setSpanKind(SpanKind.PRODUCER)
            .setNoParent()
            .startSpan();
    Context context = Context.root().with(span);
    openTelemetry
        .getPropagators()
        .getTextMapPropagator()
        .inject(context, headers, KAFKA_HEADER_SETTER);
    return new ReplayPublishSpan(span, context.makeCurrent());
  }

  private static final class ReplayPublishSpan implements AutoCloseable {

    private final Span span;
    private final Scope scope;

    private ReplayPublishSpan(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    private void failed(Throwable failure) {
      span.recordException(failure);
    }

    @Override
    public void close() {
      scope.close();
      span.end();
    }
  }
}
