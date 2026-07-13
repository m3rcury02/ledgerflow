package com.ledgerflow.orders.internal.application;

public final class IdempotencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IdempotencyConflictException() {
    super("The idempotency key was already used with a different request");
  }
}
