package com.ledgerflow.notifications.internal.application;

public final class ReplayNotAvailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ReplayNotAvailableException() {
    super("Dead-letter record is not currently replayable");
  }
}
