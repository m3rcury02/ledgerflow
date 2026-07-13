package com.ledgerflow.payments.internal.application;

public final class PaymentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PaymentNotFoundException() {
    super("The requested payment was not found");
  }
}
