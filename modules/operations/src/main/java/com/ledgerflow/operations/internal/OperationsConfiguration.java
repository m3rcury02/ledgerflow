package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.WorkTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Clock;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OperationsProperties.class, FaultInjectionProperties.class})
class OperationsConfiguration {

  @Bean
  DrainableWorkTracker drainableWorkTracker(
      OperationsProperties properties, MeterRegistry meterRegistry) {
    return new DrainableWorkTracker(properties.drainTimeout(), meterRegistry);
  }

  @Bean
  WorkTracker workTracker(DrainableWorkTracker tracker) {
    return tracker;
  }

  @Bean
  ManagedKafkaAdmin managedKafkaAdmin(KafkaAdmin kafkaAdmin) {
    return new ManagedKafkaAdmin(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
  }

  @Bean
  DependencyProbe dependencyProbe(
      JdbcClient jdbcClient, ManagedKafkaAdmin managedKafkaAdmin, OperationsProperties properties) {
    return new DependencyProbe(jdbcClient, managedKafkaAdmin, properties);
  }

  @Bean
  ReadinessProbeCache readinessProbeCache(
      DependencyProbe probe, OperationsProperties properties, MeterRegistry meterRegistry) {
    return new ReadinessProbeCache(
        probe, properties.healthProbeCacheTtl(), Clock.systemUTC(), meterRegistry);
  }

  @Bean
  MeterFilter ledgerFlowMeterFilter() {
    return new LedgerFlowMeterFilter();
  }

  @Bean
  StartupDependencyValidator startupDependencyValidator(
      DependencyProbe probe, OperationsProperties properties, Environment environment) {
    return new StartupDependencyValidator(probe, properties, environment);
  }

  @Bean("ledgerflowDependencies")
  OperationalHealthIndicator operationalHealthIndicator(
      ReadinessProbeCache readinessProbeCache,
      DrainableWorkTracker workTracker,
      Environment environment) {
    return new OperationalHealthIndicator(readinessProbeCache, workTracker, environment);
  }

  @Bean
  @Profile({"local", "test", "integration-test"})
  @ConditionalOnProperty(
      prefix = "ledgerflow.fault-injection",
      name = "enabled",
      havingValue = "true")
  FaultInjection controlledFaultInjection(FaultInjectionProperties properties) {
    return new ControlledFaultInjection(properties);
  }

  @Bean
  @ConditionalOnMissingBean(FaultInjection.class)
  FaultInjection noOpFaultInjection() {
    return point -> java.util.Objects.requireNonNull(point, "fault point must not be null");
  }
}
