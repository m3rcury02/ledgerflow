package com.ledgerflow.notifications.internal.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

final class NotificationMetrics {

  private final Map<ProcessingMetric, Counter> processing;
  private final Map<TerminalMetric, Counter> terminal;
  private final Map<ConsumerMetric, Counter> consumer;
  private final Map<ProcessingMetric, Timer> processingDelay;

  NotificationMetrics(MeterRegistry meterRegistry) {
    processing = new EnumMap<>(ProcessingMetric.class);
    processingDelay = new EnumMap<>(ProcessingMetric.class);
    for (ProcessingMetric outcome : ProcessingMetric.values()) {
      processing.put(
          outcome,
          Counter.builder("ledgerflow.notifications.effects")
              .description("Notification processing results by bounded outcome")
              .tag("outcome", outcome.tag())
              .register(meterRegistry));
      processingDelay.put(
          outcome,
          Timer.builder("ledgerflow.notifications.processing.delay")
              .description("Delay from capture occurrence to notification processing")
              .tag("outcome", outcome.tag())
              .publishPercentileHistogram()
              .register(meterRegistry));
    }
    terminal = new EnumMap<>(TerminalMetric.class);
    for (TerminalMetric outcome : TerminalMetric.values()) {
      terminal.put(
          outcome,
          Counter.builder("ledgerflow.notifications.dlt.terminal")
              .description("Terminal malformed DLT evidence results")
              .tag("outcome", outcome.tag())
              .register(meterRegistry));
    }
    consumer = new EnumMap<>(ConsumerMetric.class);
    for (ConsumerMetric outcome : ConsumerMetric.values()) {
      consumer.put(
          outcome,
          Counter.builder("ledgerflow.kafka.consumer.records")
              .description("Kafka notification-consumer records by bounded outcome")
              .tag("outcome", outcome.tag())
              .register(meterRegistry));
    }
  }

  void processing(ProcessingMetric outcome) {
    processing.get(outcome).increment();
  }

  void processingDelay(ProcessingMetric outcome, Duration delay) {
    processingDelay.get(outcome).record(delay.isNegative() ? Duration.ZERO : delay);
  }

  void terminal(TerminalMetric outcome) {
    terminal.get(outcome).increment();
  }

  void consumer(ConsumerMetric outcome) {
    consumer.get(outcome).increment();
  }

  enum ProcessingMetric {
    APPLIED("applied"),
    TRANSPORT_DUPLICATE("transport_duplicate"),
    TRANSPORT_CONFLICT("transport_conflict"),
    SEMANTIC_DUPLICATE("semantic_duplicate"),
    SEMANTIC_CONFLICT("semantic_conflict");

    private final String tag;

    ProcessingMetric(String tag) {
      this.tag = tag;
    }

    private String tag() {
      return tag;
    }
  }

  enum TerminalMetric {
    RECORDED("recorded"),
    DUPLICATE("duplicate"),
    PERSISTENCE_FAILURE("persistence_failure");

    private final String tag;

    TerminalMetric(String tag) {
      this.tag = tag;
    }

    private String tag() {
      return tag;
    }
  }

  enum ConsumerMetric {
    PROCESSED("processed"),
    RETRY("retry"),
    DLT("dlt"),
    FAILURE("failure");

    private final String tag;

    ConsumerMetric(String tag) {
      this.tag = tag;
    }

    private String tag() {
      return tag;
    }
  }
}
