package com.ledgerflow.testing;

import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ObservabilityTestConfiguration {

  @Bean
  InMemorySpanExporter inMemorySpanExporter() {
    return InMemorySpanExporter.create();
  }

  @Bean
  InMemoryLogRecordExporter inMemoryLogRecordExporter() {
    return InMemoryLogRecordExporter.create();
  }
}
