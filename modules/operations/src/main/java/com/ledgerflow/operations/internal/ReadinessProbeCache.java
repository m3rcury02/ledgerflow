package com.ledgerflow.operations.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

final class ReadinessProbeCache {

  private final DependencyProbe probe;
  private final Duration ttl;
  private final Clock clock;
  private final Object monitor = new Object();
  private final Counter readyChecks;
  private final Counter notReadyChecks;
  private final AtomicInteger currentStatus = new AtomicInteger();

  private CachedReadiness cached;
  private InFlightReadiness inFlight;

  ReadinessProbeCache(DependencyProbe probe, Duration ttl, Clock clock) {
    this(probe, ttl, clock, new SimpleMeterRegistry());
  }

  ReadinessProbeCache(
      DependencyProbe probe, Duration ttl, Clock clock, MeterRegistry meterRegistry) {
    this.probe = Objects.requireNonNull(probe, "dependency probe must not be null");
    this.ttl = Objects.requireNonNull(ttl, "cache TTL must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    readyChecks = readinessCounter(meterRegistry, "ready");
    notReadyChecks = readinessCounter(meterRegistry, "not_ready");
    Gauge.builder("ledgerflow.readiness.status", currentStatus, AtomicInteger::get)
        .description("Last dependency-readiness result, one for ready and zero for not ready")
        .register(meterRegistry);
  }

  DependencyReadiness readiness(boolean kafkaRequired) {
    InFlightReadiness current;
    boolean compute;
    synchronized (monitor) {
      Instant now = clock.instant();
      if (cached != null && cached.matches(kafkaRequired, now)) {
        return cached.readiness();
      }
      if (inFlight != null && inFlight.kafkaRequired() == kafkaRequired) {
        current = inFlight;
        compute = false;
      } else {
        current = new InFlightReadiness(kafkaRequired, new CompletableFuture<>());
        inFlight = current;
        compute = true;
      }
    }

    if (compute) {
      DependencyReadiness readiness = probe(kafkaRequired);
      synchronized (monitor) {
        cached = new CachedReadiness(kafkaRequired, readiness, clock.instant().plus(ttl));
        if (inFlight == current) {
          inFlight = null;
        }
        current.result().complete(readiness);
      }
    }
    return current.result().join();
  }

  private DependencyReadiness probe(boolean kafkaRequired) {
    try {
      probe.database();
      if (kafkaRequired) {
        probe.kafka(Set.of());
      }
      readyChecks.increment();
      currentStatus.set(1);
      return DependencyReadiness.availableResult();
    } catch (RuntimeException exception) {
      notReadyChecks.increment();
      currentStatus.set(0);
      return DependencyReadiness.unavailableResult();
    }
  }

  private Counter readinessCounter(MeterRegistry meterRegistry, String outcome) {
    return Counter.builder("ledgerflow.readiness.checks")
        .description("Uncached dependency-readiness checks by bounded outcome")
        .tag("outcome", outcome)
        .register(meterRegistry);
  }

  record DependencyReadiness(boolean available) {

    private static DependencyReadiness availableResult() {
      return new DependencyReadiness(true);
    }

    private static DependencyReadiness unavailableResult() {
      return new DependencyReadiness(false);
    }
  }

  private record CachedReadiness(
      boolean kafkaRequired, DependencyReadiness readiness, Instant expiresAt) {

    private boolean matches(boolean required, Instant now) {
      return kafkaRequired == required && now.isBefore(expiresAt);
    }
  }

  private record InFlightReadiness(
      boolean kafkaRequired, CompletableFuture<DependencyReadiness> result) {}
}
