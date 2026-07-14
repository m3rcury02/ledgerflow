package com.ledgerflow.orders.internal.web;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class OrderApiSecurityPropertiesTest {

  @Test
  void rejectsLimitsThatWouldDisableOrUnboundProtection() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties(0, Duration.ofMinutes(1), 10, DataSize.ofKilobytes(16)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties(10, Duration.ofNanos(1), 10, DataSize.ofKilobytes(16)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties(10, Duration.ofMinutes(1), 0, DataSize.ofKilobytes(16)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties(10, Duration.ofMinutes(1), 10, DataSize.ofKilobytes(17)));
  }

  private OrderApiSecurityProperties properties(
      int requests, Duration window, int principals, DataSize payload) {
    return new OrderApiSecurityProperties(requests, window, principals, payload);
  }
}
