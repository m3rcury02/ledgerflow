package com.ledgerflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerflow.messaging.internal.application.OutboxPublisher;
import com.ledgerflow.orders.api.CreateOrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflowResult;
import com.ledgerflow.testing.KafkaIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkflowKafkaIntegrationTest extends KafkaIntegrationTest {

  @Autowired private OrderWorkflow orderWorkflow;
  @Autowired private OutboxPublisher outboxPublisher;

  @Test
  void httpBusinessCompletionPrecedesAsynchronousPublicationAndIdempotentNotification() {
    OrderWorkflowResult result =
        orderWorkflow.create(
            new CreateOrderWorkflow(
                "kafka-workflow-customer",
                "kafka-workflow-correlation",
                "kafka-workflow-client",
                25_990,
                "INR",
                "pm_mock_success",
                "kafka-workflow-key-0001"));

    assertThat(result.responseStatus()).isEqualTo(201);
    assertThat(result.order().status()).isEqualTo("COMPLETED");
    assertThat(result.order().payment().status()).isEqualTo("CAPTURED");
    assertThat(outboxState()).isEqualTo("PENDING");
    assertThat(notificationCount()).isZero();

    outboxPublisher.publishBatch();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(notificationCount()).isOne());
    assertThat(outboxState()).isEqualTo("PUBLISHED");
    assertThat(inboxCount()).isOne();
  }

  private String outboxState() {
    return jdbcClient.sql("SELECT status FROM outbox_events").query(String.class).single();
  }

  private long notificationCount() {
    return jdbcClient.sql("SELECT count(*) FROM notifications").query(Long.class).single();
  }

  private long inboxCount() {
    return jdbcClient.sql("SELECT count(*) FROM notification_inbox").query(Long.class).single();
  }
}
