package com.ledgerflow.notifications.internal.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;

final class NotificationMetrics {

  private final Map<ProcessingMetric, Counter> processing;
  private final Map<TerminalMetric, Counter> terminal;

  NotificationMetrics(MeterRegistry meterRegistry) {
    processing = new EnumMap<>(ProcessingMetric.class);
    for (ProcessingMetric outcome : ProcessingMetric.values()) {
      processing.put(
          outcome,
          Counter.builder("ledgerflow.notifications.effects")
              .description("Notification processing results by bounded outcome")
              .tag("outcome", outcome.tag())
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
  }

  void processing(ProcessingMetric outcome) {
    processing.get(outcome).increment();
  }

  void terminal(TerminalMetric outcome) {
    terminal.get(outcome).increment();
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
}
