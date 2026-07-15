package com.ledgerflow.notifications.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.CatalogWriteOutcome;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.TerminalDltRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.ObjectMapper;

class DeadLetterCatalogListenerTest {

  private static final String MAIN_TOPIC = "ledgerflow.payment-captured.v1";
  private static final String DLT_TOPIC = MAIN_TOPIC + ".dlt";

  @Test
  void everyTerminalInputClassPersistsEvidenceByActualDltCoordinate() {
    JdbcNotificationStore store = mock(JdbcNotificationStore.class);
    when(store.catalogTerminal(any())).thenReturn(CatalogWriteOutcome.INSERTED);
    NotificationsProperties properties = mock(NotificationsProperties.class);
    when(properties.topic()).thenReturn(MAIN_TOPIC);
    when(properties.consumerName()).thenReturn("ledgerflow-notifications-v1");
    ObjectMapper objectMapper = new ObjectMapper();
    EventEnvelopeCodec codec = new EventEnvelopeCodec(objectMapper);
    DeadLetterCatalogListener listener =
        new DeadLetterCatalogListener(
            new NotificationEventValidator(codec),
            new DeadLetterInputClassifier(),
            codec,
            store,
            properties,
            objectMapper,
            new NotificationMetrics(new SimpleMeterRegistry()),
            Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));

    List<TerminalCase> cases =
        List.of(
            new TerminalCase(missingRoute(1L), "DLT_ORIGINAL_ROUTE_MISSING"),
            new TerminalCase(malformedRoute(2L), "DLT_ORIGINAL_ROUTE_MALFORMED"),
            new TerminalCase(unsupportedRoute(3L), "DLT_ORIGINAL_TOPIC_UNSUPPORTED"),
            new TerminalCase(withValidRoute(record(4L, ""), MAIN_TOPIC), "DLT_PAYLOAD_EMPTY"),
            new TerminalCase(
                withValidRoute(
                    record(5L, "x".repeat(NotificationEventValidator.MAX_PAYLOAD_SIZE + 1)),
                    MAIN_TOPIC),
                "DLT_PAYLOAD_TOO_LARGE"),
            new TerminalCase(
                withValidRoute(record(6L, "{not-an-event}"), MAIN_TOPIC), "DLT_EVENT_INVALID"));

    for (TerminalCase terminalCase : cases) {
      listener.catalog(terminalCase.record());

      ArgumentCaptor<TerminalDltRecord> evidence = ArgumentCaptor.forClass(TerminalDltRecord.class);
      verify(store).catalogTerminal(evidence.capture());
      assertThat(evidence.getValue().dltTopic()).isEqualTo(DLT_TOPIC);
      assertThat(evidence.getValue().dltPartition()).isZero();
      assertThat(evidence.getValue().dltOffset()).isEqualTo(terminalCase.record().offset());
      assertThat(evidence.getValue().failureCode()).isEqualTo(terminalCase.failureCode());
      clearInvocations(store);
    }
  }

  private ConsumerRecord<String, String> missingRoute(long offset) {
    return record(offset, "missing-route");
  }

  private ConsumerRecord<String, String> malformedRoute(long offset) {
    ConsumerRecord<String, String> record = withValidRoute(record(offset, "malformed"), MAIN_TOPIC);
    record
        .headers()
        .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, MAIN_TOPIC.getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private ConsumerRecord<String, String> unsupportedRoute(long offset) {
    return withValidRoute(record(offset, "unsupported"), "another.topic");
  }

  private ConsumerRecord<String, String> withValidRoute(
      ConsumerRecord<String, String> record, String originalTopic) {
    record
        .headers()
        .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add(
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
    record
        .headers()
        .add(
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            ByteBuffer.allocate(Long.BYTES).putLong(record.offset() + 100).array());
    return record;
  }

  private ConsumerRecord<String, String> record(long offset, String value) {
    return new ConsumerRecord<>(DLT_TOPIC, 0, offset, "key", value);
  }

  private record TerminalCase(ConsumerRecord<String, String> record, String failureCode) {}
}
