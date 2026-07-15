package com.ledgerflow.notifications.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

class DeadLetterInputClassifierTest {

  private static final String TOPIC = "ledgerflow.payment-captured.v1";
  private final DeadLetterInputClassifier classifier = new DeadLetterInputClassifier();

  @Test
  void acceptsOneCompleteNonNegativeOriginalRoute() {
    RecordHeaders headers = validHeaders();

    DeadLetterInputClassifier.RoutingResult result = classifier.routing(headers, TOPIC);

    assertThat(result.valid()).isTrue();
    assertThat(result.coordinates())
        .isEqualTo(new DeadLetterInputClassifier.OriginalCoordinates(TOPIC, 3, 42L));
  }

  @Test
  void classifiesMissingAndRepeatedHeadersAsTerminal() {
    RecordHeaders missing = validHeaders();
    missing.remove(KafkaHeaders.DLT_ORIGINAL_OFFSET);
    assertThat(classifier.routing(missing, TOPIC).failure())
        .isEqualTo(DeadLetterInputClassifier.TerminalFailure.ORIGINAL_ROUTE_MISSING);

    RecordHeaders repeated = validHeaders();
    repeated.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPIC.getBytes(StandardCharsets.UTF_8));
    assertThat(classifier.routing(repeated, TOPIC).failure())
        .isEqualTo(DeadLetterInputClassifier.TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
  }

  @Test
  void classifiesWrongWidthsNegativeCoordinatesAndUnsupportedTopicAsTerminal() {
    RecordHeaders wrongWidth = validHeaders();
    wrongWidth.remove(KafkaHeaders.DLT_ORIGINAL_PARTITION);
    wrongWidth.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, new byte[] {1});
    assertThat(classifier.routing(wrongWidth, TOPIC).failure())
        .isEqualTo(DeadLetterInputClassifier.TerminalFailure.ORIGINAL_ROUTE_MALFORMED);

    RecordHeaders negative = validHeaders();
    negative.remove(KafkaHeaders.DLT_ORIGINAL_OFFSET);
    negative.add(
        KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(-1).array());
    assertThat(classifier.routing(negative, TOPIC).failure())
        .isEqualTo(DeadLetterInputClassifier.TerminalFailure.ORIGINAL_ROUTE_MALFORMED);

    assertThat(classifier.routing(validHeaders(), "another.topic").failure())
        .isEqualTo(DeadLetterInputClassifier.TerminalFailure.ORIGINAL_TOPIC_UNSUPPORTED);
  }

  private RecordHeaders validHeaders() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPIC.getBytes(StandardCharsets.UTF_8));
    headers.add(
        KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(3).array());
    headers.add(
        KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(42L).array());
    return headers;
  }
}
