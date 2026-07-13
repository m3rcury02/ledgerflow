package com.ledgerflow.orders.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

  public static final int MIN_LENGTH = 8;
  public static final int MAX_LENGTH = 128;
  private static final Pattern VALID_FORMAT = Pattern.compile("[A-Za-z0-9._:-]+");

  public IdempotencyKey {
    Objects.requireNonNull(value, "Idempotency-Key must not be null");
    if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Idempotency-Key must contain 8 to 128 characters");
    }
    if (!VALID_FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Idempotency-Key contains unsupported characters");
    }
  }

  public byte[] sha256() {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }
}
