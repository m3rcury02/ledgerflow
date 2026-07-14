package com.ledgerflow.messaging.internal.application;

public class OutboxIntegrityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OutboxIntegrityException(String message) {
    super(message);
  }
}
