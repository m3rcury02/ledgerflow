package com.ledgerflow.notifications.internal.application;

public final class NotificationValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotificationValidationException(String message) {
    super(message);
  }

  public NotificationValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
