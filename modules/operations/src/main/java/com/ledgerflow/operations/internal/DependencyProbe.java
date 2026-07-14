package com.ledgerflow.operations.internal;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;

final class DependencyProbe {

  private final JdbcClient jdbcClient;
  private final KafkaAdmin kafkaAdmin;
  private final OperationsProperties properties;

  DependencyProbe(JdbcClient jdbcClient, KafkaAdmin kafkaAdmin, OperationsProperties properties) {
    this.jdbcClient = jdbcClient;
    this.kafkaAdmin = kafkaAdmin;
    this.properties = properties;
  }

  void database() {
    Integer result = jdbcClient.sql("select 1").query(Integer.class).single();
    if (result != 1) {
      throw new IllegalStateException(
          "PostgreSQL dependency validation returned an invalid result");
    }
  }

  String kafka(Set<String> requiredTopics) {
    Duration timeout = properties.dependencyTimeout();
    Map<String, Object> configuration = kafkaAdmin.getConfigurationProperties();
    Admin admin = Admin.create(configuration);
    try {
      String clusterId =
          admin.describeCluster().clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!requiredTopics.isEmpty()) {
        admin
            .describeTopics(requiredTopics)
            .allTopicNames()
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      }
      return clusterId;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka dependency validation was interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Kafka dependency is unavailable", exception);
    } finally {
      admin.close(Duration.ZERO);
    }
  }
}
