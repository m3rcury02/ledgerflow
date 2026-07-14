package com.ledgerflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerflow.messaging.internal.application.OutboxAcknowledgementHook;
import com.ledgerflow.messaging.internal.application.OutboxPublisher;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.testing.KafkaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(OutboxPublisherIntegrationTest.CrashHookConfiguration.class)
class OutboxPublisherIntegrationTest extends KafkaIntegrationTest {

  @Autowired private OutboxPublisher outboxPublisher;
  @Autowired private ToggleCrashHook crashHook;

  @Test
  void publishesAcknowledgedEnvelopeWithIdentityAndTraceHeadersThenNotifiesOnce() {
    Payment payment = confirmedPayment();
    postCapture(payment);
    UUID eventId = outboxEventId(payment.paymentId());

    outboxPublisher.publishBatch();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    ConsumerRecord<String, String> record = records(eventId, 1).getFirst();
    assertThat(record.key()).isEqualTo(payment.orderId().toString());
    assertHeader(record, "event_id", eventId.toString());
    assertHeader(record, "event_type", "com.ledgerflow.payment.captured");
    assertHeader(record, "schema_version", "1");
    assertHeader(record, "aggregate_id", payment.paymentId().toString());
    assertHeader(record, "causation_id", payment.captureRequestId().toString());
    assertThat(header(record, "x-correlation-id")).isNotBlank();
    assertThat(header(record, "traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
  }

  @Test
  void brokerAcknowledgementThenCrashRepublishesButInboxPreventsDuplicateNotification()
      throws InterruptedException {
    Payment payment = confirmedPayment();
    postCapture(payment);
    UUID eventId = outboxEventId(payment.paymentId());
    crashHook.arm();

    assertThatThrownBy(outboxPublisher::publishBatch).isInstanceOf(SimulatedPublisherCrash.class);
    assertThat(outboxStatus(eventId)).isEqualTo("IN_FLIGHT");
    Thread.sleep(2100L);

    outboxPublisher.publishBatch();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    assertThat(outboxTotalAttempts(eventId)).isEqualTo(2);
    assertThat(records(eventId, 2)).hasSizeGreaterThanOrEqualTo(2);
    assertNotificationCount(1);
    assertThat(inboxCount()).isOne();
  }

  @Test
  void concurrentPublisherInvocationsClaimOneEventOnlyOnce() throws Exception {
    Payment payment = confirmedPayment();
    postCapture(payment);
    UUID eventId = outboxEventId(payment.paymentId());

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> first = executor.submit(outboxPublisher::publishBatch);
      Future<?> second = executor.submit(outboxPublisher::publishBatch);
      first.get();
      second.get();
    }

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertNotificationCount(1));
    assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    assertThat(outboxTotalAttempts(eventId)).isOne();
  }

  private UUID outboxEventId(UUID paymentId) {
    return jdbcClient
        .sql("SELECT event_id FROM outbox_events WHERE aggregate_id = :paymentId")
        .param("paymentId", paymentId)
        .query(UUID.class)
        .single();
  }

  private String outboxStatus(UUID eventId) {
    return jdbcClient
        .sql("SELECT status FROM outbox_events WHERE event_id = :eventId")
        .param("eventId", eventId)
        .query(String.class)
        .single();
  }

  private long outboxTotalAttempts(UUID eventId) {
    return jdbcClient
        .sql("SELECT total_attempt_count FROM outbox_events WHERE event_id = :eventId")
        .param("eventId", eventId)
        .query(Long.class)
        .single();
  }

  private void assertNotificationCount(long expected) {
    assertThat(jdbcClient.sql("SELECT count(*) FROM notifications").query(Long.class).single())
        .isEqualTo(expected);
  }

  private long inboxCount() {
    return jdbcClient.sql("SELECT count(*) FROM notification_inbox").query(Long.class).single();
  }

  private List<ConsumerRecord<String, String>> records(UUID eventId, int expected) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-proof-" + UUID.randomUUID());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<ConsumerRecord<String, String>> matching = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
      consumer.subscribe(List.of(MAIN_TOPIC));
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (matching.size() < expected && System.nanoTime() < deadline) {
        consumer
            .poll(Duration.ofMillis(250))
            .forEach(
                record -> {
                  if (eventId.toString().equals(header(record, "event_id"))) {
                    matching.add(record);
                  }
                });
      }
    }
    return matching;
  }

  private void assertHeader(ConsumerRecord<String, String> record, String name, String expected) {
    assertThat(header(record, name)).isEqualTo(expected);
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CrashHookConfiguration {

    @Bean
    ToggleCrashHook outboxAcknowledgementHook() {
      return new ToggleCrashHook();
    }
  }

  static final class ToggleCrashHook implements OutboxAcknowledgementHook {

    private final AtomicBoolean armed = new AtomicBoolean();

    void arm() {
      armed.set(true);
    }

    @Override
    public void afterBrokerAcknowledgement(UUID eventId) {
      if (armed.compareAndSet(true, false)) {
        throw new SimulatedPublisherCrash();
      }
    }
  }

  static final class SimulatedPublisherCrash extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
