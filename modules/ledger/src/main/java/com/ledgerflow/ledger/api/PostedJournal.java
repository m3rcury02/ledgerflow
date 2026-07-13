package com.ledgerflow.ledger.api;

import java.time.Instant;
import java.util.UUID;

public record PostedJournal(
    UUID transactionId,
    UUID paymentId,
    UUID orderId,
    long debitTotalMinor,
    String currency,
    int entryCount,
    Instant postedAt,
    UUID reversesTransactionId,
    boolean replayed) {}
