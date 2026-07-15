package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class OutboxMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxMetrics.class);

  private final JdbcOutboxStore store;
  private final Clock clock;
  private final AtomicLong due = new AtomicLong();
  private final AtomicLong leased = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();
  private final Map<AppendOutcome, Counter> appends = new EnumMap<>(AppendOutcome.class);
  private final Map<PublishOutcome, Counter> publications = new EnumMap<>(PublishOutcome.class);
  private final Counter refreshFailures;
  private final Timer publicationDelay;

  OutboxMetrics(JdbcOutboxStore store, MeterRegistry meterRegistry, Clock clock) {
    this.store = store;
    this.clock = clock;
    registerStateGauge(meterRegistry, "due", due);
    registerStateGauge(meterRegistry, "leased", leased);
    registerStateGauge(meterRegistry, "failed", failed);
    Gauge.builder("ledgerflow.outbox.oldest.age", oldestAgeSeconds, AtomicLong::get)
        .description("Age in seconds of the oldest unpublished outbox record")
        .baseUnit("seconds")
        .register(meterRegistry);
    for (AppendOutcome outcome : AppendOutcome.values()) {
      appends.put(
          outcome,
          Counter.builder("ledgerflow.outbox.appends")
              .description("Logical outbox append results")
              .tag("outcome", outcome.tag)
              .register(meterRegistry));
    }
    for (PublishOutcome outcome : PublishOutcome.values()) {
      publications.put(
          outcome,
          Counter.builder("ledgerflow.outbox.publications")
              .description("Outbox publication results")
              .tag("outcome", outcome.tag)
              .register(meterRegistry));
    }
    refreshFailures =
        Counter.builder("ledgerflow.outbox.metrics.refresh.failures")
            .description("Failures refreshing the bounded outbox backlog snapshot")
            .register(meterRegistry);
    publicationDelay =
        Timer.builder("ledgerflow.outbox.publication.delay")
            .description("Delay from business occurrence to acknowledged Kafka publication")
            .publishPercentileHistogram()
            .register(meterRegistry);
  }

  @Scheduled(
      fixedDelayString = "${ledgerflow.messaging.metrics-refresh-interval:15s}",
      initialDelayString = "${ledgerflow.messaging.metrics-refresh-initial-delay:1s}")
  void refresh() {
    try {
      JdbcOutboxStore.BacklogSnapshot snapshot = store.backlog(clock.instant());
      due.set(snapshot.due());
      leased.set(snapshot.leased());
      failed.set(snapshot.failed());
      oldestAgeSeconds.set(snapshot.oldestAgeSeconds());
    } catch (RuntimeException exception) {
      refreshFailures.increment();
      LOGGER
          .atWarn()
          .addKeyValue("event_code", "OUTBOX_METRICS_REFRESH_FAILED")
          .addKeyValue("action", "outbox.metrics.refresh")
          .addKeyValue("error_code", "DEPENDENCY_UNAVAILABLE")
          .log("Outbox metric snapshot refresh failed");
    }
  }

  void append(AppendOutcome outcome) {
    appends.get(outcome).increment();
  }

  void appendAfterCommit(AppendOutcome outcome) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      append(outcome);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            append(
                status == TransactionSynchronization.STATUS_COMMITTED
                    ? outcome
                    : AppendOutcome.FAILURE);
          }
        });
  }

  void publish(PublishOutcome outcome) {
    publications.get(outcome).increment();
  }

  void publishedAfter(java.time.Duration delay) {
    publicationDelay.record(delay.isNegative() ? java.time.Duration.ZERO : delay);
  }

  private void registerStateGauge(MeterRegistry meterRegistry, String state, AtomicLong value) {
    Gauge.builder("ledgerflow.outbox.records", value, AtomicLong::get)
        .description("Outbox records by bounded operational state")
        .tag("state", state)
        .register(meterRegistry);
  }

  enum AppendOutcome {
    CREATED("created"),
    DUPLICATE("duplicate"),
    CONFLICT("conflict"),
    FAILURE("failure");

    private final String tag;

    AppendOutcome(String tag) {
      this.tag = tag;
    }
  }

  enum PublishOutcome {
    PUBLISHED("published"),
    RETRY("retry"),
    FAILED("failed"),
    INVALID("invalid"),
    STALE_OWNER("stale_owner");

    private final String tag;

    PublishOutcome(String tag) {
      this.tag = tag;
    }
  }
}
