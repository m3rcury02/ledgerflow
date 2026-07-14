package com.ledgerflow.messaging.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

  private final OutboxRetryPolicy retryPolicy =
      new OutboxRetryPolicy(
          new MessagingProperties(
              "events",
              "events.dlt",
              25,
              Duration.ofSeconds(30),
              Duration.ofSeconds(10),
              10,
              Duration.ofSeconds(1),
              Duration.ofSeconds(256),
              0.0));

  @Test
  void boundsTheTenAttemptExponentialRetryCycle() {
    assertThat(retryPolicy.exhausted(9)).isFalse();
    assertThat(retryPolicy.exhausted(10)).isTrue();
    assertThat(retryPolicy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(retryPolicy.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(retryPolicy.delayAfter(9)).isEqualTo(Duration.ofSeconds(256));
  }
}
