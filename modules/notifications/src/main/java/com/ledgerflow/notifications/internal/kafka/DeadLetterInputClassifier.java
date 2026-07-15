package com.ledgerflow.notifications.internal.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

final class DeadLetterInputClassifier {

  RoutingResult routing(Headers headers, String expectedOriginalTopic) {
    HeaderValue topic =
        requireOne(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.ORIGINAL_TOPIC);
    HeaderValue partition =
        requireOne(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION, KafkaHeaders.ORIGINAL_PARTITION);
    HeaderValue offset =
        requireOne(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET, KafkaHeaders.ORIGINAL_OFFSET);
    if (topic.failure() != null || partition.failure() != null || offset.failure() != null) {
      TerminalFailure failure =
          topic.failure() == TerminalFailure.ORIGINAL_ROUTE_MISSING
                  || partition.failure() == TerminalFailure.ORIGINAL_ROUTE_MISSING
                  || offset.failure() == TerminalFailure.ORIGINAL_ROUTE_MISSING
              ? TerminalFailure.ORIGINAL_ROUTE_MISSING
              : TerminalFailure.ORIGINAL_ROUTE_MALFORMED;
      return RoutingResult.failed(failure);
    }

    String originalTopic;
    try {
      originalTopic =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(topic.value()))
              .toString();
    } catch (CharacterCodingException exception) {
      return RoutingResult.failed(TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
    }
    if (!originalTopic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")) {
      return RoutingResult.failed(TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
    }
    if (!expectedOriginalTopic.equals(originalTopic)) {
      return RoutingResult.failed(TerminalFailure.ORIGINAL_TOPIC_UNSUPPORTED);
    }
    if (partition.value().length != Integer.BYTES || offset.value().length != Long.BYTES) {
      return RoutingResult.failed(TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
    }
    int originalPartition = ByteBuffer.wrap(partition.value()).getInt();
    long originalOffset = ByteBuffer.wrap(offset.value()).getLong();
    if (originalPartition < 0 || originalOffset < 0) {
      return RoutingResult.failed(TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
    }
    return RoutingResult.valid(
        new OriginalCoordinates(originalTopic, originalPartition, originalOffset));
  }

  private HeaderValue requireOne(Headers headers, String first, String second) {
    List<Header> matches = new ArrayList<>();
    headers.headers(first).forEach(matches::add);
    headers.headers(second).forEach(matches::add);
    if (matches.isEmpty()) {
      return HeaderValue.failed(TerminalFailure.ORIGINAL_ROUTE_MISSING);
    }
    if (matches.size() != 1 || matches.getFirst().value() == null) {
      return HeaderValue.failed(TerminalFailure.ORIGINAL_ROUTE_MALFORMED);
    }
    return HeaderValue.valid(matches.getFirst().value());
  }

  enum TerminalFailure {
    ORIGINAL_ROUTE_MISSING(
        "DLT_ORIGINAL_ROUTE_MISSING", "Required original-routing evidence is missing."),
    ORIGINAL_ROUTE_MALFORMED(
        "DLT_ORIGINAL_ROUTE_MALFORMED", "Original-routing evidence is malformed."),
    ORIGINAL_TOPIC_UNSUPPORTED(
        "DLT_ORIGINAL_TOPIC_UNSUPPORTED", "The original topic is not supported."),
    PAYLOAD_EMPTY("DLT_PAYLOAD_EMPTY", "The DLT payload is empty."),
    PAYLOAD_TOO_LARGE("DLT_PAYLOAD_TOO_LARGE", "The DLT payload exceeds the catalog limit."),
    EVENT_INVALID("DLT_EVENT_INVALID", "The DLT event contract or identity is invalid.");

    private final String code;
    private final String summary;

    TerminalFailure(String code, String summary) {
      this.code = code;
      this.summary = summary;
    }

    String code() {
      return code;
    }

    String summary() {
      return summary;
    }
  }

  record OriginalCoordinates(String topic, int partition, long offset) {}

  record RoutingResult(OriginalCoordinates coordinates, TerminalFailure failure) {

    private static RoutingResult valid(OriginalCoordinates coordinates) {
      return new RoutingResult(coordinates, null);
    }

    private static RoutingResult failed(TerminalFailure failure) {
      return new RoutingResult(null, failure);
    }

    boolean valid() {
      return coordinates != null;
    }
  }

  private record HeaderValue(byte[] value, TerminalFailure failure) {

    private static HeaderValue valid(byte[] value) {
      return new HeaderValue(value, null);
    }

    private static HeaderValue failed(TerminalFailure failure) {
      return new HeaderValue(null, failure);
    }
  }
}
