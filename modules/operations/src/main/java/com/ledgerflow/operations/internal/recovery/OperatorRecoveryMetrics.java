package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class OperatorRecoveryMetrics {

  private final MeterRegistry registry;
  private final Map<String, Counter> counters = new ConcurrentHashMap<>();
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong inProgress = new AtomicLong();
  private final AtomicLong waiting = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong oldestActiveAge = new AtomicLong();

  OperatorRecoveryMetrics(MeterRegistry registry) {
    this.registry = registry;
    registerState("pending", pending);
    registerState("in_progress", inProgress);
    registerState("waiting", waiting);
    registerState("failed", failed);
    Gauge.builder("ledgerflow.operator.oldest.active.age.seconds", oldestActiveAge, AtomicLong::get)
        .description("Age in seconds of the oldest active operator retry command")
        .register(registry);
  }

  void retry(OperationType type, String outcome) {
    String operation = type.name().toLowerCase(Locale.ROOT);
    counters
        .computeIfAbsent(
            "retry:" + operation + ':' + outcome,
            ignored ->
                Counter.builder("ledgerflow.operator.retries")
                    .description("Bounded operator retry command outcomes")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry))
        .increment();
  }

  void takeover(OperationType type) {
    String operation = type.name().toLowerCase(Locale.ROOT);
    counters
        .computeIfAbsent(
            "takeover:" + operation,
            ignored ->
                Counter.builder("ledgerflow.operator.lease.takeovers")
                    .description("Expired operator retry lease takeovers")
                    .tag("operation", operation)
                    .register(registry))
        .increment();
  }

  void breakGlass(String outcome) {
    counters
        .computeIfAbsent(
            "breakglass:" + outcome,
            ignored ->
                Counter.builder("ledgerflow.operator.breakglass")
                    .description("Break-glass approval and use outcomes")
                    .tag("outcome", outcome)
                    .register(registry))
        .increment();
  }

  void refresh(OperatorRecoverySnapshot snapshot) {
    pending.set(snapshot.pending());
    inProgress.set(snapshot.inProgress());
    waiting.set(snapshot.waiting());
    failed.set(snapshot.failed());
    oldestActiveAge.set(snapshot.oldestActiveAgeSeconds());
  }

  private void registerState(String state, AtomicLong value) {
    Gauge.builder("ledgerflow.operator.commands", value, AtomicLong::get)
        .description("Durable operator retry commands by bounded state")
        .tag("state", state)
        .register(registry);
  }
}
