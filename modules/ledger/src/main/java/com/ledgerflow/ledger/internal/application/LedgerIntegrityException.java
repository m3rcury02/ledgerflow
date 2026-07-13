package com.ledgerflow.ledger.internal.application;

public final class LedgerIntegrityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LedgerIntegrityException(String message) {
    super(message);
  }
}
