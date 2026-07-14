package com.ledgerflow.notifications.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class RetrySequenceBackOffTest {

  @Test
  void performsExactlyThreeRetriesAtTheConfiguredIntervals() {
    BackOffExecution execution =
        new RetrySequenceBackOff(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))
            .start();

    assertThat(execution.nextBackOff()).isEqualTo(1_000L);
    assertThat(execution.nextBackOff()).isEqualTo(5_000L);
    assertThat(execution.nextBackOff()).isEqualTo(30_000L);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }
}
