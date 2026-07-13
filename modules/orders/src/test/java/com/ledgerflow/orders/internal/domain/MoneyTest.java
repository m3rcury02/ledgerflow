package com.ledgerflow.orders.internal.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void acceptsPositiveInrMinorUnits() {
    new Money(259_900, "INR");
  }

  @Test
  void rejectsNonPositiveMinorUnits() {
    assertThatThrownBy(() -> new Money(0, "INR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void restrictsTheMvpToInr() {
    assertThatThrownBy(() -> new Money(100, "USD"))
        .isInstanceOf(UnsupportedCurrencyException.class)
        .hasMessageContaining("INR");
  }
}
