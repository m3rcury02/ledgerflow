package com.ledgerflow.operations.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class ReadinessProbeCache {

  private final DependencyProbe probe;
  private final Duration ttl;
  private final Clock clock;
  private final Object monitor = new Object();

  private CachedReadiness cached;
  private InFlightReadiness inFlight;

  ReadinessProbeCache(DependencyProbe probe, Duration ttl, Clock clock) {
    this.probe = Objects.requireNonNull(probe, "dependency probe must not be null");
    this.ttl = Objects.requireNonNull(ttl, "cache TTL must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
      return DependencyReadiness.availableResult();
    } catch (RuntimeException exception) {
      return DependencyReadiness.unavailableResult();
    }
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
