package com.ledgerflow.orders.internal.application;

import java.util.UUID;

public record IdempotencyRecord(
    boolean claimed,
    byte[] requestHash,
    String state,
    UUID resourceId,
    Integer responseStatus,
    String responseLocation,
    String responseBody) {

  public IdempotencyRecord {
    requestHash = requestHash.clone();
  }

  @Override
  public byte[] requestHash() {
    return requestHash.clone();
  }
}
