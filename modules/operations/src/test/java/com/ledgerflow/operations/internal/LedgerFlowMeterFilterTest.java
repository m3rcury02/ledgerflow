package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LedgerFlowMeterFilterTest {

  @Test
  void rejectsUnboundedLedgerFlowLabels() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().meterFilter(new LedgerFlowMeterFilter());

    assertThatThrownBy(
            () ->
                Counter.builder("ledgerflow.orders.workflow")
                    .tag("order_id", "019abcdef")
                    .register(registry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("order_id");

    assertThatThrownBy(
            () ->
                Counter.builder("ledgerflow.orders.workflow")
                    .tag("outcome", "customer-controlled-value")
                    .register(registry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome");
  }
}
