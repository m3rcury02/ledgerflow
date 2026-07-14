package com.ledgerflow.notifications.internal.kafka;

final class DeadLetterCatalogUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  DeadLetterCatalogUnavailableException(Exception cause) {
    super("Dead-letter cataloging failed after bounded retries", cause);
  }
}
