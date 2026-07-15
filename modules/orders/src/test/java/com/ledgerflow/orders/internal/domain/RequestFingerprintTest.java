package com.ledgerflow.orders.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  @Test
  void isDeterministicForTheSameCanonicalPayload() {
    byte[] first = RequestFingerprint.create("checkout-1", new Money(10_000, "INR"));
    byte[] second = RequestFingerprint.create("checkout-1", new Money(10_000, "INR"));

    assertThat(first).hasSize(32).containsExactly(second);
  }

  @Test
  void distinguishesFieldsAndNullFromEmpty() {
    Money money = new Money(10_000, "INR");

    assertThat(RequestFingerprint.create(null, money))
        .isNotEqualTo(RequestFingerprint.create("", money));
    assertThat(RequestFingerprint.create("checkout-1", money))
        .isNotEqualTo(RequestFingerprint.create("checkout-2", money));
    assertThat(RequestFingerprint.create("checkout-1", money))
        .isNotEqualTo(RequestFingerprint.create("checkout-1", new Money(10_001, "INR")));
  }

  @Test
  void completeWorkflowFingerprintIncludesTheOpaquePaymentReference() {
    Money money = new Money(10_000, "INR");

    assertThat(RequestFingerprint.createWorkflow("checkout-1", money, "pm_mock_success"))
        .hasSize(32)
        .isNotEqualTo(
            RequestFingerprint.createWorkflow(
                "checkout-1", money, "pm_mock_authorization_decline"));
  }
}
