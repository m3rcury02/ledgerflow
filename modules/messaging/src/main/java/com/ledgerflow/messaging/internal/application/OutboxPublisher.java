package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.internal.kafka.KafkaTracePropagation;
import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import com.ledgerflow.messaging.internal.persistence.OutboxRecord;
import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.InjectedFaultException;
import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

  private final JdbcOutboxStore outboxStore;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final MessagingProperties properties;
  private final EventEnvelopeCodec codec;
  private final OutboxRetryPolicy retryPolicy;
  private final KafkaTracePropagation tracePropagation;
  private final OutboxAcknowledgementHook acknowledgementHook;
  private final Clock clock;
  private final String workerId;
  private final WorkTracker workTracker;
  private final FaultInjection faultInjection;
  private final OutboxMetrics metrics;

  public OutboxPublisher(
      JdbcOutboxStore outboxStore,
      KafkaTemplate<String, String> kafkaTemplate,
      MessagingProperties properties,
      EventEnvelopeCodec codec,
      KafkaTracePropagation tracePropagation,
      OutboxAcknowledgementHook acknowledgementHook,
      Clock clock,
      WorkTracker workTracker,
      FaultInjection faultInjection,
      OutboxMetrics metrics) {
    this.outboxStore = outboxStore;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
    this.codec = codec;
    this.retryPolicy = new OutboxRetryPolicy(properties);
    this.tracePropagation = tracePropagation;
    this.acknowledgementHook = acknowledgementHook;
    this.clock = clock;
    this.workerId = UUID.randomUUID().toString();
    this.workTracker = workTracker;
    this.faultInjection = faultInjection;
    this.metrics = metrics;
  }

  @Scheduled(
      scheduler = "outboxTaskScheduler",
      fixedDelayString = "${ledgerflow.messaging.publisher-poll-interval:1s}",
      initialDelayString = "${ledgerflow.messaging.publisher-initial-delay:1s}")
  public void publishBatch() {
    WorkToken work = workTracker.begin("outbox-publish-batch");
    try {
      String leaseOwner = workerId + ":" + UUID.randomUUID();
      List<OutboxRecord> records =
          outboxStore.claimBatch(
              leaseOwner, properties.batchSize(), clock.instant(), properties.leaseDuration());
      for (OutboxRecord record : records) {
        publish(record);
      }
    } finally {
      metrics.refresh();
      work.close();
    }
  }

  private void publish(OutboxRecord record) {
    String canonicalPayload;
    try {
      canonicalPayload = codec.serialize(codec.deserialize(record.payload()));
    } catch (IllegalArgumentException exception) {
      recordPermanentFailure(record, "OUTBOX_PAYLOAD_INVALID");
      return;
    }
    if (!MessageDigest.isEqual(codec.hash(canonicalPayload), record.payloadHash())) {
      recordPermanentFailure(record, "OUTBOX_PAYLOAD_HASH_MISMATCH");
      return;
    }
    ProducerRecord<String, String> producerRecord =
        new ProducerRecord<>(record.topic(), record.eventKey(), canonicalPayload);
    addIdentityHeaders(producerRecord, record);
    try (KafkaTracePropagation.PublishSpan span =
        tracePropagation.start(record, producerRecord.headers())) {
      try {
        faultInjection.before(FaultPoint.OUTBOX_PUBLISH);
        kafkaTemplate
            .send(producerRecord)
            .get(properties.acknowledgementTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        span.failed("kafka_send_interrupted");
        recordPublishFailure(record, "KAFKA_SEND_INTERRUPTED");
        return;
      } catch (ExecutionException | TimeoutException exception) {
        span.failed("kafka_send_failed");
        recordPublishFailure(record, "KAFKA_SEND_FAILED");
        return;
      } catch (InjectedFaultException exception) {
        span.failed("fault_injected");
        recordPublishFailure(record, "OUTBOX_FAULT_INJECTED");
        return;
      }

      acknowledgementHook.afterBrokerAcknowledgement(record.eventId());
      boolean marked =
          outboxStore.markPublished(record.eventId(), record.leaseOwner(), clock.instant());
      if (!marked) {
        metrics.publish(OutboxMetrics.PublishOutcome.STALE_OWNER);
        LOGGER
            .atWarn()
            .addKeyValue("event_code", "OUTBOX_ACKNOWLEDGEMENT_STALE_OWNER")
            .addKeyValue("action", "outbox.publish")
            .addKeyValue("error_code", "STALE_OWNER")
            .addKeyValue("correlation_id", record.correlationId())
            .log("Outbox acknowledgement was not marked by its lease owner");
      } else {
        metrics.publish(OutboxMetrics.PublishOutcome.PUBLISHED);
        metrics.publishedAfter(Duration.between(record.occurredAt(), clock.instant()));
      }
    }
  }

  private void recordPublishFailure(OutboxRecord record, String code) {
    boolean exhausted = retryPolicy.exhausted(record.cycleAttemptCount());
    Duration delay = exhausted ? Duration.ZERO : retryPolicy.delayAfter(record.cycleAttemptCount());
    boolean recorded =
        outboxStore.markFailed(
            record.eventId(), record.leaseOwner(), clock.instant(), exhausted, delay, code);
    if (!recorded) {
      metrics.publish(OutboxMetrics.PublishOutcome.STALE_OWNER);
      LOGGER
          .atWarn()
          .addKeyValue("event_code", "OUTBOX_FAILURE_STALE_OWNER")
          .addKeyValue("action", "outbox.publish")
          .addKeyValue("error_code", "STALE_OWNER")
          .addKeyValue("correlation_id", record.correlationId())
          .log("Outbox failure was not recorded by its lease owner");
    } else {
      metrics.publish(
          exhausted ? OutboxMetrics.PublishOutcome.FAILED : OutboxMetrics.PublishOutcome.RETRY);
    }
  }

  private void recordPermanentFailure(OutboxRecord record, String code) {
    boolean recorded =
        outboxStore.markFailed(
            record.eventId(), record.leaseOwner(), clock.instant(), true, Duration.ZERO, code);
    metrics.publish(
        recorded ? OutboxMetrics.PublishOutcome.INVALID : OutboxMetrics.PublishOutcome.STALE_OWNER);
  }

  private void addIdentityHeaders(
      ProducerRecord<String, String> producerRecord, OutboxRecord record) {
    addHeader(producerRecord, "event_id", record.eventId().toString());
    addHeader(producerRecord, "event_type", record.eventType());
    addHeader(producerRecord, "schema_version", Integer.toString(record.schemaVersion()));
    addHeader(producerRecord, "aggregate_id", record.aggregateId().toString());
    addHeader(producerRecord, "x-correlation-id", record.correlationId());
    addHeader(producerRecord, "causation_id", record.causationId().toString());
  }

  private void addHeader(ProducerRecord<String, String> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }
}
