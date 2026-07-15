package com.ledgerflow.notifications.internal.persistence;

import java.time.Instant;

public record TerminalDltRecord(
    String consumerName,
    String dltTopic,
    int dltPartition,
    long dltOffset,
    byte[] keyHash,
    int keySize,
    byte[] payloadHash,
    int payloadSize,
    String safeHeaders,
    String failureCode,
    String failureSummary,
    Instant observedAt) {}
