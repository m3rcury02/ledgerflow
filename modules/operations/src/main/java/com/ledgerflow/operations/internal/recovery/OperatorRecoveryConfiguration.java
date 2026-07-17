package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.operations.internal.OperationsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class OperatorRecoveryConfiguration {

  @Bean
  OperatorRecoveryStore operatorRecoveryStore(JdbcClient jdbcClient) {
    return new OperatorRecoveryStore(jdbcClient);
  }

  @Bean
  OperatorRecoveryMetrics operatorRecoveryMetrics(MeterRegistry meterRegistry) {
    return new OperatorRecoveryMetrics(meterRegistry);
  }

  @Bean
  OperatorRecoveryService operatorRecoveryService(
      OperatorRecoveryStore store,
      OperationsProperties properties,
      OperatorRecoveryMetrics metrics) {
    return new OperatorRecoveryService(store, properties, metrics, Clock.systemUTC());
  }

  @Bean
  OperatorRetryWorker operatorRetryWorker(
      OperatorRecoveryStore store,
      List<OperationRecoveryHandler> handlers,
      OperationsProperties properties,
      OperatorRecoveryMetrics metrics,
      WorkTracker workTracker,
      OpenTelemetry openTelemetry) {
    return new OperatorRetryWorker(
        store, handlers, properties, metrics, workTracker, Clock.systemUTC(), openTelemetry);
  }
}
