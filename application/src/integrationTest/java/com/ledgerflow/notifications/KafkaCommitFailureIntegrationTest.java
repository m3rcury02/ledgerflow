package com.ledgerflow.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.InjectedFaultException;
import com.ledgerflow.testing.KafkaIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;

@Import(KafkaCommitFailureIntegrationTest.FaultConfiguration.class)
class KafkaCommitFailureIntegrationTest extends KafkaIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ToggleFaultInjection faultInjection;

  @BeforeEach
  void resetFaults() {
    faultInjection.reset();
  }

  @Test
  void databaseCommitFollowedByOffsetFailureRedeliversWithoutDuplicateSideEffect()
      throws Exception {
    faultInjection.failOnce(FaultPoint.NOTIFICATION_OFFSET_COMMIT);

    kafkaTemplate.send(validRecord()).get();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(faultInjection.matchingCalls()).isGreaterThanOrEqualTo(2);
              assertThat(count("notification_inbox")).isOne();
              assertThat(count("notifications")).isOne();
              assertThat(count("dead_letter_records")).isZero();
            });
  }

  @Test
  void dltPublicationFailureRetainsTheSourceRecordUntilPublicationRecovers() throws Exception {
    faultInjection.failContinuously(FaultPoint.NOTIFICATION_DLT_PUBLISH);
    kafkaTemplate
        .send(new ProducerRecord<>(MAIN_TOPIC, UUID.randomUUID().toString(), "{not-json"))
        .get();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(faultInjection.matchingCalls()).isGreaterThanOrEqualTo(2));
    assertThat(count("dead_letter_records")).isZero();
    assertThat(count("terminal_dlt_records")).isZero();

    faultInjection.reset();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(count("terminal_dlt_records")).isOne();
              assertThat(count("dead_letter_records")).isZero();
            });
  }

  private ProducerRecord<String, String> validRecord() {
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID causationId = UUID.randomUUID();
    String occurredAt = "2026-07-17T10:00:00Z";
    String payload =
        """
        {
          "eventId":"%s",
          "eventType":"com.ledgerflow.payment.captured",
          "schemaVersion":1,
          "aggregateId":"%s",
          "correlationId":"consumer-offset-proof",
          "causationId":"%s",
          "occurredAt":"%s",
          "data":{
            "orderId":"%s",
            "paymentId":"%s",
            "ledgerTransactionId":"%s",
            "amountMinor":2599,
            "currency":"INR",
            "capturedAt":"%s"
          }
        }
        """
            .formatted(
                eventId,
                paymentId,
                causationId,
                occurredAt,
                orderId,
                paymentId,
                UUID.randomUUID(),
                occurredAt);
    ProducerRecord<String, String> record =
        new ProducerRecord<>(MAIN_TOPIC, orderId.toString(), payload);
    addHeader(record, "event_id", eventId.toString());
    addHeader(record, "event_type", "com.ledgerflow.payment.captured");
    addHeader(record, "schema_version", "1");
    addHeader(record, "aggregate_id", paymentId.toString());
    addHeader(record, "causation_id", causationId.toString());
    addHeader(record, "x-correlation-id", "consumer-offset-proof");
    return record;
  }

  private void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FaultConfiguration {

    @Bean
    @Primary
    ToggleFaultInjection proofFaultInjection() {
      return new ToggleFaultInjection();
    }
  }

  static final class ToggleFaultInjection implements FaultInjection {

    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean continuous = new AtomicBoolean();
    private final AtomicInteger matchingCalls = new AtomicInteger();
    private final AtomicInteger remainingFailures = new AtomicInteger();
    private volatile FaultPoint point;

    void failOnce(FaultPoint point) {
      arm(point, false);
    }

    void failContinuously(FaultPoint point) {
      arm(point, true);
    }

    private void arm(FaultPoint point, boolean continuous) {
      this.point = point;
      this.continuous.set(continuous);
      matchingCalls.set(0);
      remainingFailures.set(continuous ? 0 : 1);
      armed.set(true);
    }

    void reset() {
      armed.set(false);
      continuous.set(false);
      matchingCalls.set(0);
      remainingFailures.set(0);
      point = null;
    }

    int matchingCalls() {
      return matchingCalls.get();
    }

    @Override
    public void before(FaultPoint candidate) {
      if (!armed.get() || point != candidate) {
        return;
      }
      matchingCalls.incrementAndGet();
      if (continuous.get() || remainingFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
        throw new InjectedFaultException(candidate);
      }
    }
  }
}
