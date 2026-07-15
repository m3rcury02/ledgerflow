package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReadinessProbeCacheTest {

  @Test
  void oneHundredConcurrentRequestsShareOneDatabaseAndKafkaProbe() throws Exception {
    DependencyProbe probe = mock(DependencyProbe.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test probe release timed out");
              }
              return null;
            })
        .when(probe)
        .database();
    ReadinessProbeCache cache =
        new ReadinessProbeCache(probe, Duration.ofSeconds(2), Clock.systemUTC());

    try (var executor = Executors.newFixedThreadPool(100)) {
      List<Future<ReadinessProbeCache.DependencyReadiness>> results = new ArrayList<>();
      for (int index = 0; index < 100; index++) {
        results.add(executor.submit(() -> cache.readiness(true)));
      }
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      for (Future<ReadinessProbeCache.DependencyReadiness> result : results) {
        assertThat(result.get(2, TimeUnit.SECONDS).available()).isTrue();
      }
    }

    verify(probe, times(1)).database();
    verify(probe, times(1)).kafka(Set.of());
  }

  @Test
  void cachesFailureAndRecoversAfterTheTtl() {
    DependencyProbe probe = mock(DependencyProbe.class);
    doThrow(new IllegalStateException("unavailable")).doNothing().when(probe).database();
    MutableClock clock = new MutableClock(Instant.parse("2026-07-15T11:00:00Z"));
    ReadinessProbeCache cache = new ReadinessProbeCache(probe, Duration.ofSeconds(2), clock);

    assertThat(cache.readiness(false).available()).isFalse();
    assertThat(cache.readiness(false).available()).isFalse();
    verify(probe, times(1)).database();

    clock.advance(Duration.ofSeconds(2));

    assertThat(cache.readiness(false).available()).isTrue();
    verify(probe, times(2)).database();
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    private MutableClock(Instant instant) {
      this.instant = new AtomicReference<>(instant);
    }

    private void advance(Duration duration) {
      instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
