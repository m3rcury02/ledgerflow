package com.ledgerflow.orders.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestFingerprint {

  private static final byte VERSION = 1;

  private RequestFingerprint() {
    // Static utility.
  }

  public static byte[] create(String clientReference, Money money) {
    return createCanonical(VERSION, clientReference, money, null);
  }

  public static byte[] createWorkflow(
      String clientReference, Money money, String paymentMethodReference) {
    if (paymentMethodReference == null) {
      throw new IllegalArgumentException("payment method reference must not be null");
    }
    return createCanonical((byte) 2, clientReference, money, paymentMethodReference);
  }

  private static byte[] createCanonical(
      byte version, String clientReference, Money money, String paymentMethodReference) {
    byte[] reference =
        clientReference == null ? new byte[0] : clientReference.getBytes(StandardCharsets.UTF_8);
    byte[] currency = money.currency().getBytes(StandardCharsets.US_ASCII);
    byte[] paymentReference =
        paymentMethodReference == null
            ? new byte[0]
            : paymentMethodReference.getBytes(StandardCharsets.US_ASCII);
    ByteBuffer canonical =
        ByteBuffer.allocate(
            1
                + 1
                + Integer.BYTES
                + reference.length
                + Long.BYTES
                + currency.length
                + (version >= 2 ? Integer.BYTES + paymentReference.length : 0));
    canonical.put(version);
    canonical.put((byte) (clientReference == null ? 0 : 1));
    canonical.putInt(reference.length);
    canonical.put(reference);
    canonical.putLong(money.amountMinor());
    canonical.put(currency);
    if (version >= 2) {
      canonical.putInt(paymentReference.length);
      canonical.put(paymentReference);
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(canonical.array());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }
}
