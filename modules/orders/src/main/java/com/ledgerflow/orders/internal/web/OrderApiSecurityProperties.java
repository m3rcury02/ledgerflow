package com.ledgerflow.orders.internal.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("ledgerflow.security")
public record OrderApiSecurityProperties(
    int writeRequestsPerWindow,
    Duration writeWindow,
    int maxTrackedPrincipals,
    DataSize maxWritePayload) {

  private static final int MAX_REQUESTS_PER_WINDOW = 1_000_000;
  private static final int MAX_TRACKED_PRINCIPALS = 1_000_000;
  private static final Duration MINIMUM_WINDOW = Duration.ofMillis(1);
  private static final Duration MAXIMUM_WINDOW = Duration.ofDays(1);
  private static final long MAX_SUPPORTED_PAYLOAD_BYTES = 16L * 1024L;

  public OrderApiSecurityProperties {
    if (writeRequestsPerWindow < 1 || writeRequestsPerWindow > MAX_REQUESTS_PER_WINDOW) {
      throw new IllegalArgumentException("writeRequestsPerWindow must contain 1 to 1000000");
    }
    if (writeWindow == null
        || writeWindow.compareTo(MINIMUM_WINDOW) < 0
        || writeWindow.compareTo(MAXIMUM_WINDOW) > 0) {
      throw new IllegalArgumentException("writeWindow must contain 1 millisecond to 1 day");
    }
    if (maxTrackedPrincipals < 1 || maxTrackedPrincipals > MAX_TRACKED_PRINCIPALS) {
      throw new IllegalArgumentException("maxTrackedPrincipals must contain 1 to 1000000");
    }
    if (maxWritePayload == null
        || maxWritePayload.toBytes() < 1
        || maxWritePayload.toBytes() > MAX_SUPPORTED_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("maxWritePayload must contain 1 byte to 16 KiB");
    }
  }
}
