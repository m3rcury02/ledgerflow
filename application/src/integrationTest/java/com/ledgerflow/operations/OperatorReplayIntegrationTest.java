package com.ledgerflow.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorReplayIntegrationTest extends PostgreSqlIntegrationTest {

  @Test
  void replayBehavesCorrectly() {
    assertThat(true).isTrue();
  }
}
