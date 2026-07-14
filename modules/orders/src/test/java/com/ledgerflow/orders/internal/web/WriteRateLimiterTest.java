package com.ledgerflow.orders.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class WriteRateLimiterTest {

  @Test
  void rejectsAboveTheConfiguredLimitAndRecoversAfterTheWindow() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-14T07:31:00Z"));
    WriteRateLimiter limiter = new WriteRateLimiter(properties(2, 10), clock);

    assertThat(limiter.acquire("subject:customer-one").allowed()).isTrue();
    assertThat(limiter.acquire("subject:customer-one").allowed()).isTrue();
    WriteRateLimiter.Decision rejected = limiter.acquire("subject:customer-one");
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMinutes(1));

    clock.advance(Duration.ofMinutes(1));

    assertThat(limiter.acquire("subject:customer-one").allowed()).isTrue();
  }

  @Test
  void boundsTrackedPrincipalsAndReclaimsExpiredEntries() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-14T07:31:00Z"));
    WriteRateLimiter limiter = new WriteRateLimiter(properties(5, 2), clock);

    assertThat(limiter.acquire("subject:one").allowed()).isTrue();
    assertThat(limiter.acquire("subject:two").allowed()).isTrue();
    assertThat(limiter.acquire("subject:three").allowed()).isFalse();
    assertThat(limiter.trackedPrincipalCount()).isEqualTo(2);

    clock.advance(Duration.ofMinutes(1));

    assertThat(limiter.acquire("subject:three").allowed()).isTrue();
    assertThat(limiter.trackedPrincipalCount()).isOne();
  }

  private OrderApiSecurityProperties properties(int capacity, int maximumPrincipals) {
    return new OrderApiSecurityProperties(
        capacity, Duration.ofMinutes(1), maximumPrincipals, DataSize.ofKilobytes(16));
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!getZone().equals(zone)) {
        throw new IllegalArgumentException("Only UTC is supported by this test clock");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }
  }
}
