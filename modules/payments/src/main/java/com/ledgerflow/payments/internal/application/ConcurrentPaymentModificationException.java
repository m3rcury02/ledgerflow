package com.ledgerflow.payments.internal.application;

public final class ConcurrentPaymentModificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConcurrentPaymentModificationException() {
    super("The payment was changed concurrently");
  }
}
