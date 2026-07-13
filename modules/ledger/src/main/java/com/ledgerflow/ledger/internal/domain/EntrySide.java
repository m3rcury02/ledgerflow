package com.ledgerflow.ledger.internal.domain;

public enum EntrySide {
  DEBIT("D"),
  CREDIT("C");

  private final String databaseCode;

  EntrySide(String databaseCode) {
    this.databaseCode = databaseCode;
  }

  public String databaseCode() {
    return databaseCode;
  }

  public EntrySide opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }

  public static EntrySide fromDatabaseCode(String code) {
    return switch (code) {
      case "D" -> DEBIT;
      case "C" -> CREDIT;
      default -> throw new IllegalArgumentException("Unknown journal entry side");
    };
  }
}
