package com.ledgerflow.orders.internal.domain;

public final class UnsupportedCurrencyException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public UnsupportedCurrencyException(String currency) {
    super("The MVP supports INR only; received " + currency);
  }
}
