package com.ledgerflow.notifications.internal.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

final class KafkaEventHeaders {

  static final String AGGREGATE_ID = "aggregate_id";
  static final String CAUSATION_ID = "causation_id";
  static final String CORRELATION_ID = "x-correlation-id";
  static final String EVENT_ID = "event_id";
  static final String EVENT_TYPE = "event_type";
  static final String LEDGERFLOW_DELIVERY_ATTEMPT = "ledgerflow_delivery_attempt";
  static final String REPLAY_CAUSATION_ID = "replay_causation_id";
  static final String REPLAY_REQUEST_ID = "replay_request_id";
  static final String SCHEMA_VERSION = "schema_version";
  static final String TRACEPARENT = "traceparent";
  static final String TRACESTATE = "tracestate";

  private KafkaEventHeaders() {
    throw new AssertionError("not instantiable");
  }

  static String requireSingleText(Headers headers, String name) {
    List<Header> matching = headers(headers, name);
    if (matching.size() != 1 || matching.getFirst().value() == null) {
      throw new IllegalArgumentException("Required Kafka identity header is missing or repeated");
    }
    return new String(matching.getFirst().value(), StandardCharsets.UTF_8);
  }

  static String optionalLastText(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  static String originalTopic(Headers headers) {
    return requireOriginalText(
        headers, KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.ORIGINAL_TOPIC);
  }

  static int originalPartition(Headers headers) {
    byte[] value =
        requireOriginal(
            headers, KafkaHeaders.DLT_ORIGINAL_PARTITION, KafkaHeaders.ORIGINAL_PARTITION);
    if (value.length != Integer.BYTES) {
      throw new IllegalArgumentException("Original Kafka partition header is invalid");
    }
    return ByteBuffer.wrap(value).getInt();
  }

  static long originalOffset(Headers headers) {
    byte[] value =
        requireOriginal(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET, KafkaHeaders.ORIGINAL_OFFSET);
    if (value.length != Long.BYTES) {
      throw new IllegalArgumentException("Original Kafka offset header is invalid");
    }
    return ByteBuffer.wrap(value).getLong();
  }

  static int deliveryAttempt(Headers headers) {
    Header header = headers.lastHeader(LEDGERFLOW_DELIVERY_ATTEMPT);
    if (header == null) {
      header = headers.lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
    }
    if (header == null || header.value() == null) {
      return 1;
    }
    if (header.value().length == Integer.BYTES) {
      return Math.max(1, ByteBuffer.wrap(header.value()).getInt());
    }
    try {
      return Math.max(1, Integer.parseInt(new String(header.value(), StandardCharsets.US_ASCII)));
    } catch (NumberFormatException exception) {
      return 1;
    }
  }

  static void addText(Headers headers, String name, String value) {
    headers.add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private static String requireOriginalText(Headers headers, String first, String second) {
    return new String(requireOriginal(headers, first, second), StandardCharsets.UTF_8);
  }

  private static byte[] requireOriginal(Headers headers, String first, String second) {
    Header header = headers.lastHeader(first);
    if (header == null) {
      header = headers.lastHeader(second);
    }
    if (header == null || header.value() == null) {
      throw new IllegalArgumentException("Original Kafka coordinate header is missing");
    }
    return header.value();
  }

  private static List<Header> headers(Headers headers, String name) {
    List<Header> matching = new ArrayList<>();
    headers.headers(name).forEach(matching::add);
    return matching;
  }
}
