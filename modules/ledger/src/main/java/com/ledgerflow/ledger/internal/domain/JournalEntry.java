package com.ledgerflow.ledger.internal.domain;

import java.util.Objects;

public record JournalEntry(String accountCode, EntrySide side, long amountMinor, String currency) {

  public JournalEntry {
    Objects.requireNonNull(side, "side must not be null");
    if (accountCode == null || !accountCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("account code is invalid");
    }
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("journal entry amount must be positive");
    }
    if (currency == null || !currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("journal entry currency is invalid");
    }
  }

  public JournalEntry reverse() {
    return new JournalEntry(accountCode, side.opposite(), amountMinor, currency);
  }
}
