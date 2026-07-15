package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.api.AppendPaymentCapturedEvent;
import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.OutboxEventAppender;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class PaymentCapturedOutboxAppender implements OutboxEventAppender {

  private final JdbcOutboxStore outboxStore;
  private final EventEnvelopeCodec codec;
  private final MessagingProperties properties;
  private final OutboxMetrics metrics;
  private final OpenTelemetry openTelemetry;

  public PaymentCapturedOutboxAppender(
      JdbcOutboxStore outboxStore,
      EventEnvelopeCodec codec,
      MessagingProperties properties,
      OutboxMetrics metrics,
      OpenTelemetry openTelemetry) {
    this.outboxStore = outboxStore;
    this.codec = codec;
    this.properties = properties;
    this.metrics = metrics;
    this.openTelemetry = openTelemetry;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentCaptured(AppendPaymentCapturedEvent command) {
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.messaging")
            .spanBuilder("outbox.append")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("db.system.name", "postgresql")
            .startSpan();
    Scope scope = span.makeCurrent();
    try {
      java.util.Optional<UUID> existing =
          outboxStore.findPaymentCapturedEventId(command.paymentId());
      UUID eventId = existing.orElseGet(outboxStore::nextEventId);
      PaymentCapturedEventV1 event =
          new PaymentCapturedEventV1(
              eventId,
              PaymentCapturedEventV1.TYPE,
              PaymentCapturedEventV1.SCHEMA_VERSION,
              command.paymentId(),
              command.correlationId(),
              command.causationId(),
              command.occurredAt(),
              new PaymentCapturedDataV1(
                  command.orderId(),
                  command.paymentId(),
                  command.ledgerTransactionId(),
                  command.amountMinor(),
                  command.currency(),
                  command.occurredAt()));
      String payload = codec.serialize(event);
      OutboxTraceContext trace = OutboxTraceContext.capture(span);
      UUID persisted =
          outboxStore.insertOrVerify(
              event,
              properties.topic(),
              command.orderId().toString(),
              payload,
              codec.hash(payload),
              trace.traceparent(),
              trace.tracestate());
      OutboxMetrics.AppendOutcome outcome =
          existing.isPresent() || !persisted.equals(eventId)
              ? OutboxMetrics.AppendOutcome.DUPLICATE
              : OutboxMetrics.AppendOutcome.CREATED;
      metrics.appendAfterCommit(outcome);
      span.setAttribute("ledgerflow.outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
      return persisted;
    } catch (OutboxIntegrityException exception) {
      metrics.append(OutboxMetrics.AppendOutcome.CONFLICT);
      span.setStatus(StatusCode.ERROR, "conflict");
      throw exception;
    } catch (RuntimeException exception) {
      metrics.append(OutboxMetrics.AppendOutcome.FAILURE);
      span.setStatus(StatusCode.ERROR, "outbox_append_failed");
      throw exception;
    } finally {
      scope.close();
      span.end();
    }
  }
}
