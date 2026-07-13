package com.ledgerflow.ledger.internal.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record JournalPosting(
    JournalType journalType,
    String sourceType,
    UUID sourceId,
    UUID paymentId,
    UUID orderId,
    String currency,
    UUID reversesTransactionId,
    String description,
    String correlationId,
    String actor,
    List<JournalEntry> entries) {

  public static final String PAYMENT_CLEARING = "PAYMENT_CLEARING";
  public static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";

  public JournalPosting {
    Objects.requireNonNull(journalType, "journal type must not be null");
    Objects.requireNonNull(sourceId, "source ID must not be null");
    Objects.requireNonNull(paymentId, "payment ID must not be null");
    Objects.requireNonNull(orderId, "order ID must not be null");
    if (sourceType == null || !sourceType.matches("[A-Z][A-Z0-9_]{2,31}")) {
      throw new IllegalArgumentException("source type is invalid");
    }
    if (currency == null || !currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("journal currency is invalid");
    }
    if (description == null || description.isBlank() || description.length() > 200) {
      throw new IllegalArgumentException("journal description is invalid");
    }
    validateCorrelationId(correlationId);
    validateActor(actor);
    entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    if (entries.size() < 2) {
      throw new IllegalArgumentException("journal transaction requires at least two entries");
    }
    long debitTotal = 0;
    long creditTotal = 0;
    try {
      for (JournalEntry entry : entries) {
        if (!currency.equals(entry.currency())) {
          throw new IllegalArgumentException("journal entries must use one currency");
        }
        if (entry.side() == EntrySide.DEBIT) {
          debitTotal = Math.addExact(debitTotal, entry.amountMinor());
        } else {
          creditTotal = Math.addExact(creditTotal, entry.amountMinor());
        }
      }
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          "journal total exceeds integer minor-unit range", exception);
    }
    if (debitTotal != creditTotal) {
      throw new IllegalArgumentException("journal debits must equal credits");
    }
    if (journalType == JournalType.PAYMENT_CAPTURE) {
      if (!"PAYMENT_CAPTURE".equals(sourceType)
          || !sourceId.equals(paymentId)
          || reversesTransactionId != null) {
        throw new IllegalArgumentException("payment capture source metadata is invalid");
      }
    } else if (!"LEDGER_CORRECTION".equals(sourceType)
        || reversesTransactionId == null
        || !sourceId.equals(reversesTransactionId)) {
      throw new IllegalArgumentException("correction source metadata is invalid");
    }
  }

  public static JournalPosting paymentCapture(
      UUID paymentId,
      UUID orderId,
      long amountMinor,
      String currency,
      String correlationId,
      String actor) {
    return new JournalPosting(
        JournalType.PAYMENT_CAPTURE,
        "PAYMENT_CAPTURE",
        paymentId,
        paymentId,
        orderId,
        currency,
        null,
        "Payment capture",
        correlationId,
        actor,
        List.of(
            new JournalEntry(PAYMENT_CLEARING, EntrySide.DEBIT, amountMinor, currency),
            new JournalEntry(MERCHANT_PAYABLE, EntrySide.CREDIT, amountMinor, currency)));
  }

  public long debitTotalMinor() {
    return entries.stream()
        .filter(entry -> entry.side() == EntrySide.DEBIT)
        .mapToLong(JournalEntry::amountMinor)
        .reduce(0, Math::addExact);
  }

  private static void validateCorrelationId(String value) {
    if (value == null || value.length() > 64 || !value.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("correlation ID is invalid");
    }
  }

  private static void validateActor(String value) {
    if (value == null || value.length() > 100 || !value.matches("[A-Za-z0-9._:@-]+")) {
      throw new IllegalArgumentException("journal actor is invalid");
    }
  }
}
