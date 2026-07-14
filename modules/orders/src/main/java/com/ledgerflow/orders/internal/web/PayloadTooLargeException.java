package com.ledgerflow.orders.internal.web;

import java.io.IOException;

final class PayloadTooLargeException extends IOException {

  private static final long serialVersionUID = 1L;

  PayloadTooLargeException() {
    super("Request payload exceeded its configured limit");
  }
}
