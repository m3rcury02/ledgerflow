package com.ledgerflow.orders.internal.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

final class WriteRateLimiter {

  private final int capacity;
  private final long windowMillis;
  private final int maxTrackedPrincipals;
  private final Clock clock;
  private final Map<String, Window> windows = new HashMap<>();

  WriteRateLimiter(OrderApiSecurityProperties properties, Clock clock) {
    this.capacity = properties.writeRequestsPerWindow();
    this.windowMillis = properties.writeWindow().toMillis();
    this.maxTrackedPrincipals = properties.maxTrackedPrincipals();
    this.clock = clock;
  }

  synchronized Decision acquire(String principalKey) {
    long now = clock.millis();
    String keyHash = hash(principalKey);
    Window current = windows.get(keyHash);
    if (current == null || now >= current.expiresAt()) {
      removeExpired(now);
      if (current == null && windows.size() >= maxTrackedPrincipals) {
        return Decision.rejected(Duration.ofMillis(windowMillis));
      }
      windows.put(keyHash, new Window(1, safeAdd(now, windowMillis)));
      return Decision.permitted();
    }
    if (current.requests() >= capacity) {
      return Decision.rejected(Duration.ofMillis(Math.max(1, current.expiresAt() - now)));
    }
    windows.put(keyHash, new Window(current.requests() + 1, current.expiresAt()));
    return Decision.permitted();
  }

  synchronized int trackedPrincipalCount() {
    return windows.size();
  }

  private void removeExpired(long now) {
    windows.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
  }

  private long safeAdd(long value, long increment) {
    try {
      return Math.addExact(value, increment);
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  private String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }

  record Decision(boolean allowed, Duration retryAfter) {

    static Decision permitted() {
      return new Decision(true, Duration.ZERO);
    }

    static Decision rejected(Duration retryAfter) {
      return new Decision(false, retryAfter);
    }
  }

  private record Window(int requests, long expiresAt) {}
}
