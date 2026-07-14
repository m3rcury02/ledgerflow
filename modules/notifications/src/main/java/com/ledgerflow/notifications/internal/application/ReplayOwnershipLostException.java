package com.ledgerflow.notifications.internal.application;

public final class ReplayOwnershipLostException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ReplayOwnershipLostException() {
    super("Dead-letter replay ownership was lost");
  }
}
