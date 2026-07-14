package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.api.AppendPaymentCapturedEvent;
import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.OutboxEventAppender;
import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class PaymentCapturedOutboxAppender implements OutboxEventAppender {

  private final JdbcOutboxStore outboxStore;
  private final EventEnvelopeCodec codec;
  private final MessagingProperties properties;

  public PaymentCapturedOutboxAppender(
      JdbcOutboxStore outboxStore, EventEnvelopeCodec codec, MessagingProperties properties) {
    this.outboxStore = outboxStore;
    this.codec = codec;
    this.properties = properties;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentCaptured(AppendPaymentCapturedEvent command) {
    UUID eventId =
        outboxStore
            .findPaymentCapturedEventId(command.paymentId())
            .orElseGet(outboxStore::nextEventId);
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
    OutboxTraceContext trace = OutboxTraceContext.capture();
    return outboxStore.insertOrVerify(
        event,
        properties.topic(),
        command.orderId().toString(),
        payload,
        codec.hash(payload),
        trace.traceparent(),
        trace.tracestate());
  }
}
