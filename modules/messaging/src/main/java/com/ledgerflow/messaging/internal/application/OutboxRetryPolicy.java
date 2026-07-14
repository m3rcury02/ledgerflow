package com.ledgerflow.messaging.internal.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class OutboxRetryPolicy {

  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration maxBackoff;
  private final double jitterRatio;

  public OutboxRetryPolicy(MessagingProperties properties) {
    this.maxAttempts = properties.maxPublishAttempts();
    this.baseBackoff = properties.publisherBaseBackoff();
    this.maxBackoff = properties.publisherMaxBackoff();
    this.jitterRatio = properties.publisherJitterRatio();
  }

  public boolean exhausted(int completedAttempts) {
    return completedAttempts >= maxAttempts;
  }

  public Duration delayAfter(int completedAttempts) {
    int exponent = Math.min(Math.max(completedAttempts - 1, 0), 30);
    long multiplier = 1L << exponent;
    long cappedMillis;
    try {
      cappedMillis =
          Math.min(Math.multiplyExact(baseBackoff.toMillis(), multiplier), maxBackoff.toMillis());
    } catch (ArithmeticException exception) {
      cappedMillis = maxBackoff.toMillis();
    }
    double jitter =
        jitterRatio == 0.0
            ? 0.0
            : ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
    long jitteredMillis = Math.max(1L, Math.round(cappedMillis * (1.0 + jitter)));
    return Duration.ofMillis(Math.min(Math.max(1L, maxBackoff.toMillis()), jitteredMillis));
  }
}
