package com.ledgerflow.payments.api;

public final class PaymentNotReadyForAccountingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PaymentNotReadyForAccountingException() {
    super("Payment capture is not ready for ledger accounting");
  }
}
