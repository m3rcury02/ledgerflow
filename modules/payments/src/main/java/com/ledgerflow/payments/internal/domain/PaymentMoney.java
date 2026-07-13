package com.ledgerflow.payments.internal.domain;

import java.util.Objects;

public record PaymentMoney(long amountMinor, String currency) {

  public PaymentMoney {
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    Objects.requireNonNull(currency, "currency must not be null");
    if (!"INR".equals(currency)) {
      throw new IllegalArgumentException("The MVP supports INR only");
    }
  }
}
