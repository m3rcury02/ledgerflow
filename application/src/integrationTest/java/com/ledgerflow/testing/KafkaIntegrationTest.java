package com.ledgerflow.testing;

import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class KafkaIntegrationTest extends LedgerIntegrationTestSupport {

  protected static final String MAIN_TOPIC = "ledgerflow.payment-captured.v1";
  protected static final String DLT_TOPIC = MAIN_TOPIC + ".dlt";

  private static final KafkaContainer KAFKA;

  static {
    KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    KAFKA.start();
    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(MAIN_TOPIC, 1, (short) 1), new NewTopic(DLT_TOPIC, 1, (short) 1)))
          .all()
          .get();
    } catch (Exception exception) {
      throw new IllegalStateException("Kafka integration topics could not be created", exception);
    }
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.listener.auto-startup", () -> "true");
    registry.add("ledgerflow.messaging.publisher-enabled", () -> "true");
    registry.add("ledgerflow.messaging.publisher-initial-delay", () -> "1h");
    registry.add("ledgerflow.messaging.publisher-poll-interval", () -> "1h");
    registry.add("ledgerflow.messaging.lease-duration", () -> "2s");
    registry.add("ledgerflow.messaging.acknowledgement-timeout", () -> "1s");
    registry.add("ledgerflow.messaging.publisher-base-backoff", () -> "100ms");
    registry.add("ledgerflow.messaging.publisher-max-backoff", () -> "1s");
    registry.add("ledgerflow.messaging.publisher-jitter-ratio", () -> "0");
    registry.add("ledgerflow.notifications.enabled", () -> "true");
    registry.add("ledgerflow.notifications.dlt-enabled", () -> "true");
    registry.add("ledgerflow.notifications.first-retry-backoff", () -> "100ms");
    registry.add("ledgerflow.notifications.second-retry-backoff", () -> "100ms");
    registry.add("ledgerflow.notifications.third-retry-backoff", () -> "100ms");
    registry.add("ledgerflow.notifications.broker-acknowledgement-timeout", () -> "1s");
    registry.add("ledgerflow.notifications.replay-lease-duration", () -> "2s");
  }

  protected static String kafkaBootstrapServers() {
    return KAFKA.getBootstrapServers();
  }
}
