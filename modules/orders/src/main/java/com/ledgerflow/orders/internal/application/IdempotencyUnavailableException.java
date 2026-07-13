package com.ledgerflow.orders.internal.application;

public final class IdempotencyUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IdempotencyUnavailableException() {
    super("The original idempotent operation has not completed");
  }
}
