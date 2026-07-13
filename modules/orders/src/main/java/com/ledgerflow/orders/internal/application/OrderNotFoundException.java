package com.ledgerflow.orders.internal.application;

public final class OrderNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrderNotFoundException() {
    super("The requested order was not found");
  }
}
