package com.ledgerflow.notifications.internal.application;

public final class NotificationSemanticConflictException extends NotificationIntegrityException {

  private static final long serialVersionUID = 1L;

  public NotificationSemanticConflictException(String message) {
    super(message);
  }
}
