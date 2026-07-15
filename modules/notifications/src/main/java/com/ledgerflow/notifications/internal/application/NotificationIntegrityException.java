package com.ledgerflow.notifications.internal.application;

public class NotificationIntegrityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotificationIntegrityException(String message) {
    super(message);
  }

  public NotificationIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
