package com.ledgerflow.operations.internal;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.jdbc.core.simple.JdbcClient;

final class DependencyProbe {

  private final JdbcClient jdbcClient;
  private final ManagedKafkaAdmin managedKafkaAdmin;
  private final OperationsProperties properties;

  DependencyProbe(
      JdbcClient jdbcClient, ManagedKafkaAdmin managedKafkaAdmin, OperationsProperties properties) {
    this.jdbcClient = jdbcClient;
    this.managedKafkaAdmin = managedKafkaAdmin;
    this.properties = properties;
  }

  void database() {
    int timeoutSeconds = Math.max(1, Math.toIntExact(properties.dependencyTimeout().toSeconds()));
    Integer result =
        jdbcClient.sql("select 1").withQueryTimeout(timeoutSeconds).query(Integer.class).single();
    if (result != 1) {
      throw new IllegalStateException(
          "PostgreSQL dependency validation returned an invalid result");
    }
  }

  String kafka(Set<String> requiredTopics) {
    Duration timeout = properties.dependencyTimeout();
    Admin admin = managedKafkaAdmin.client();
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
    }
  }
}
