package com.ledgerflow.payments.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentRetryPolicyTest {

  @Test
  void appliesExponentialBackoffCapAndDeterministicJitter() {
    PaymentRetryPolicy policy =
        new PaymentRetryPolicy(
            2,
            Duration.ofMillis(100),
            Duration.ofMillis(150),
            2.0,
            0.2,
            () -> 1.0,
            duration -> assertThat(duration).isNotNull());

    assertThat(policy.delayAfterAttempt(1)).isEqualTo(Duration.ofMillis(120));
    assertThat(policy.delayAfterAttempt(2)).isEqualTo(Duration.ofMillis(150));
  }

  @Test
  void delegatesTheBoundedDelayToTheSleeper() {
    List<Duration> delays = new ArrayList<>();
    PaymentRetryPolicy policy =
        new PaymentRetryPolicy(
            2, Duration.ofMillis(100), Duration.ofSeconds(1), 2.0, 0.0, () -> 0.5, delays::add);

    policy.pauseAfterAttempt(1);

    assertThat(delays).containsExactly(Duration.ofMillis(100));
  }

  @Test
  void enforcesTheMvpAutomaticRetryBound() {
    assertThatThrownBy(
            () ->
                new PaymentRetryPolicy(
                    3,
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1),
                    2.0,
                    0.2,
                    () -> 0.5,
                    duration -> assertThat(duration).isNotNull()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 or 2");
  }
}
