package com.ledgerflow.payments.internal.domain;

public final class IllegalPaymentTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IllegalPaymentTransitionException(PaymentState from, PaymentState to) {
    super("Illegal payment transition from " + from + " to " + to);
  }
}
