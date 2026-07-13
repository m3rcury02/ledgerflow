package com.ledgerflow.payments.internal.application;

import java.time.Duration;
import java.util.function.DoubleSupplier;

public final class PaymentRetryPolicy {

  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration maxBackoff;
  private final double multiplier;
  private final double jitterRatio;
  private final DoubleSupplier random;
  private final Sleeper sleeper;

  public PaymentRetryPolicy(
      int maxAttempts,
      Duration baseBackoff,
      Duration maxBackoff,
      double multiplier,
      double jitterRatio,
      DoubleSupplier random,
      Sleeper sleeper) {
    if (maxAttempts < 1 || maxAttempts > 2) {
      throw new IllegalArgumentException("maxAttempts must be 1 or 2");
    }
    if (baseBackoff.isNegative() || baseBackoff.isZero()) {
      throw new IllegalArgumentException("baseBackoff must be positive");
    }
    if (maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException("maxBackoff must not be less than baseBackoff");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be at least 1");
    }
    if (jitterRatio < 0.0 || jitterRatio > 1.0) {
      throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
    }
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
    this.maxBackoff = maxBackoff;
    this.multiplier = multiplier;
    this.jitterRatio = jitterRatio;
    this.random = random;
    this.sleeper = sleeper;
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  public Duration delayAfterAttempt(int completedAttempts) {
    double exponential = Math.pow(multiplier, Math.max(0, completedAttempts - 1));
    long exponentialNanos = Math.round(baseBackoff.toNanos() * exponential);
    double jitterFactor = 1.0 + ((random.getAsDouble() * 2.0) - 1.0) * jitterRatio;
    long jitteredNanos = Math.max(1, Math.round(exponentialNanos * jitterFactor));
    return Duration.ofNanos(Math.min(maxBackoff.toNanos(), jitteredNanos));
  }

  public void pauseAfterAttempt(int completedAttempts) {
    try {
      sleeper.sleep(delayAfterAttempt(completedAttempts));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Payment retry backoff was interrupted", exception);
    }
  }

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
