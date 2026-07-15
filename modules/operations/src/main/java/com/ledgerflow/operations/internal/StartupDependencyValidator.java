package com.ledgerflow.operations.internal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

final class StartupDependencyValidator implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(StartupDependencyValidator.class);
  private static final Set<String> FAULT_PROFILES = Set.of("local", "test", "integration-test");

  private final DependencyProbe probe;
  private final OperationsProperties properties;
  private final Environment environment;

  StartupDependencyValidator(
      DependencyProbe probe, OperationsProperties properties, Environment environment) {
    this.probe = probe;
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    rejectSharedManagementPort();
    rejectProductionFaultInjection();
    if (!properties.startupValidationEnabled()) {
      return;
    }
    probe.database();
    if (kafkaRequired()) {
      String clusterId = probe.kafka(requiredTopics());
      LOGGER.info("Startup dependency validation succeeded: kafkaClusterId={}", clusterId);
    }
  }

  private void rejectSharedManagementPort() {
    Integer managementPort = environment.getProperty("management.server.port", Integer.class);
    int applicationPort = environment.getProperty("server.port", Integer.class, 8080);
    if (managementPort != null && managementPort > 0 && managementPort == applicationPort) {
      throw new IllegalStateException(
          "management.server.port must differ from the application server port");
    }
  }

  private void rejectProductionFaultInjection() {
    if (!environment.getProperty("ledgerflow.fault-injection.enabled", Boolean.class, false)) {
      return;
    }
    boolean allowed =
        Arrays.stream(environment.getActiveProfiles()).anyMatch(FAULT_PROFILES::contains);
    if (!allowed) {
      throw new IllegalStateException(
          "Controlled fault injection is allowed only in local and test profiles");
    }
  }

  private boolean kafkaRequired() {
    return environment.getProperty("ledgerflow.messaging.publisher-enabled", Boolean.class, false)
        || environment.getProperty("ledgerflow.notifications.enabled", Boolean.class, false);
  }

  private Set<String> requiredTopics() {
    Set<String> topics = new LinkedHashSet<>();
    if (environment.getProperty("ledgerflow.messaging.publisher-enabled", Boolean.class, false)) {
      topics.add(
          environment.getProperty("ledgerflow.messaging.topic", "ledgerflow.payment-captured.v1"));
    }
    if (environment.getProperty("ledgerflow.notifications.enabled", Boolean.class, false)) {
      topics.add(
          environment.getProperty(
              "ledgerflow.notifications.topic", "ledgerflow.payment-captured.v1"));
      if (environment.getProperty("ledgerflow.notifications.dlt-enabled", Boolean.class, false)) {
        topics.add(
            environment.getProperty(
                "ledgerflow.notifications.dead-letter-topic",
                "ledgerflow.payment-captured.v1.dlt"));
      }
    }
    return Set.copyOf(topics);
  }
}
