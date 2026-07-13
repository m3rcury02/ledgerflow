package com.ledgerflow.ledger.internal.application;

public final class JournalNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JournalNotFoundException() {
    super("Journal transaction was not found");
  }
}
