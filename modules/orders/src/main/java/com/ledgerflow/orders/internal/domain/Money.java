package com.ledgerflow.orders.internal.domain;

import java.util.Objects;

public record Money(long amountMinor, String currency) {

  public Money {
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    Objects.requireNonNull(currency, "currency must not be null");
    if (!"INR".equals(currency)) {
      throw new UnsupportedCurrencyException(currency);
    }
  }
}
