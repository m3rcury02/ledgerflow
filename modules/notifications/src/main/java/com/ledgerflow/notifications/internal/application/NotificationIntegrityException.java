package com.ledgerflow.notifications.internal.application;

public final class NotificationIntegrityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotificationIntegrityException(String message) {
    super(message);
  }

  public NotificationIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
