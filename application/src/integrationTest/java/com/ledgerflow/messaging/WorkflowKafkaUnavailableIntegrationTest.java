package com.ledgerflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.orders.api.CreateOrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflowResult;
import com.ledgerflow.testing.KafkaIntegrationTest;
import com.ledgerflow.testing.payment.MockPaymentProviderServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class WorkflowKafkaUnavailableIntegrationTest extends KafkaIntegrationTest {

  private static final MockPaymentProviderServer PROVIDER = new MockPaymentProviderServer();

  @Autowired private OrderWorkflow orderWorkflow;

  @DynamicPropertySource
  static void paymentProvider(DynamicPropertyRegistry registry) {
    registry.add("ledgerflow.payment.provider.base-url", PROVIDER::baseUrl);
    registry.add("ledgerflow.payment.provider.connect-timeout", () -> "100ms");
    registry.add("ledgerflow.payment.provider.read-timeout", () -> "750ms");
    registry.add("ledgerflow.payment.provider.overall-timeout", () -> "1s");
    registry.add("ledgerflow.payment.provider.circuit-failure-threshold", () -> "10");
    registry.add("ledgerflow.payment.provider.circuit-sliding-window-size", () -> "10");
    registry.add("ledgerflow.payment.provider.max-attempts", () -> "2");
    registry.add("ledgerflow.payment.provider.base-backoff", () -> "1ms");
    registry.add("ledgerflow.payment.provider.max-backoff", () -> "2ms");
    registry.add("ledgerflow.payment.provider.jitter-ratio", () -> "0");
  }

  @BeforeEach
  void resetProvider() {
    PROVIDER.reset();
  }

  @Test
  void runtimeKafkaOutageCannotRollbackCompletedBusinessStateOrDurableOutbox() {
    OrderWorkflowResult result;
    pauseKafka();
    try {
      result =
          orderWorkflow.create(
              new CreateOrderWorkflow(
                  "unavailable-kafka-customer",
                  "unavailable-kafka-correlation",
                  null,
                  25_990,
                  "INR",
                  "pm_mock_success",
                  "unavailable-kafka-key-0001"));
    } finally {
      unpauseKafka();
    }

    assertThat(result.responseStatus()).isEqualTo(201);
    assertThat(result.order().status()).isEqualTo("COMPLETED");
    assertThat(jdbcClient.sql("SELECT state FROM payments").query(String.class).single())
        .isEqualTo("CAPTURED");
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM ledger_transactions").query(Long.class).single())
        .isOne();
    assertThat(jdbcClient.sql("SELECT status FROM outbox_events").query(String.class).single())
        .isEqualTo("PENDING");
  }
}
