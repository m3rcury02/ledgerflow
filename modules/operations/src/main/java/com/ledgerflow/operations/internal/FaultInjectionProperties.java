package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.api.FaultPoint;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerflow.fault-injection")
record FaultInjectionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("PAYMENT_PROVIDER") FaultPoint point,
    @DefaultValue("FAIL") Mode mode,
    @DefaultValue("100ms") Duration delay) {

  enum Mode {
    DELAY,
    FAIL
  }

  FaultInjectionProperties {
    if (point == null || mode == null) {
      throw new IllegalArgumentException("fault injection point and mode are required");
    }
    if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofSeconds(10)) > 0) {
      throw new IllegalArgumentException("fault injection delay must be between zero and 10s");
    }
  }
}
