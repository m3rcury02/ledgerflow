package com.ledgerflow.notifications.internal.application;

import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.kafka.NotificationEventValidator;
import com.ledgerflow.notifications.internal.kafka.ValidatedNotificationEvent;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.ReplayClaim;
import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
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

public class DeadLetterReplayService implements OperationRecoveryHandler {

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
  public OperationType operationType() {
    return OperationType.DEAD_LETTER;
  }

  @Override
  public OperationRecoveryResult recover(OperationRecoveryContext context) {
    context.leaseGuard().requireCurrent();
    String leaseOwner = "replay:" + UUID.randomUUID();
    ReplayClaim claim =
        store.claimReplay(
            context.sourceId(),
            context.commandId(),
            leaseOwner,
            context.correlationId(),
            clock.instant(),
            properties.replayLeaseDuration(),
            context.leaseGuard());

    ValidatedNotificationEvent validated;
    try {
      validated = validator.validateStored(claim.eventKey(), claim.validatedPayload());
      if (!claim.eventId().equals(validated.event().eventId())) {
        throw new NotificationValidationException(
            "Catalog event identity does not match its validated payload");
      }
    } catch (RuntimeException exception) {
      context.leaseGuard().requireCurrent();
      store.markReplayFailed(
          claim,
          "REPLAY_PAYLOAD_INVALID",
          "Cataloged replay payload failed validation.",
          clock.instant(),
          context.leaseGuard());
      return OperationRecoveryResult.failed("REPLAY_PAYLOAD_INVALID");
    }

    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            properties.topic(), validated.eventKey(), validated.canonicalPayload());
    addFreshHeaders(record, validated.event(), context.commandId(), context.correlationId());
    context.leaseGuard().requireCurrent();
    try (ReplayPublishSpan span = startPublishSpan(validated.event(), record.headers())) {
      try {
        kafkaTemplate
            .send(record)
            .get(properties.brokerAcknowledgementTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        span.failed("replay_interrupted");
        context.leaseGuard().requireCurrent();
        store.markReplayFailed(
            claim, "REPLAY_INTERRUPTED", FAILURE_SUMMARY, clock.instant(), context.leaseGuard());
        return OperationRecoveryResult.failed("REPLAY_INTERRUPTED");
      } catch (ExecutionException | TimeoutException exception) {
        span.failed("replay_publish_failed");
        context.leaseGuard().requireCurrent();
        store.markReplayFailed(
            claim, "REPLAY_PUBLISH_FAILED", FAILURE_SUMMARY, clock.instant(), context.leaseGuard());
        return OperationRecoveryResult.failed("REPLAY_PUBLISH_FAILED");
      } catch (RuntimeException exception) {
        span.failed("replay_publish_failed");
        context.leaseGuard().requireCurrent();
        store.markReplayFailed(
            claim, "REPLAY_PUBLISH_FAILED", FAILURE_SUMMARY, clock.instant(), context.leaseGuard());
        return OperationRecoveryResult.failed("REPLAY_PUBLISH_FAILED");
      }
    }

    context.leaseGuard().requireCurrent();
    store.markReplayPublished(claim, clock.instant(), context.leaseGuard());
    return OperationRecoveryResult.completed("DEAD_LETTER_REPLAYED");
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
            .spanBuilder("deadletter.replay.publish")
            .setSpanKind(SpanKind.PRODUCER)
            .setParent(Context.current())
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

    private void failed(String errorCode) {
      span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, errorCode);
    }

    @Override
    public void close() {
      scope.close();
      span.end();
    }
  }
}
