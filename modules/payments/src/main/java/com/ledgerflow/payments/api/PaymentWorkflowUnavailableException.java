package com.ledgerflow.payments.api;

public final class PaymentWorkflowUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PaymentWorkflowUnavailableException() {
    super("The payment provider is not configured");
  }
}
