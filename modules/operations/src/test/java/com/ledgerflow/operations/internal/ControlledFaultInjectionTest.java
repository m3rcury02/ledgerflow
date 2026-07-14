package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.InjectedFaultException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ControlledFaultInjectionTest {

  @Test
  void failureAppliesOnlyToTheExplicitlyAllowlistedPoint() {
    ControlledFaultInjection injection =
        new ControlledFaultInjection(
            new FaultInjectionProperties(
                true,
                FaultPoint.PAYMENT_PROVIDER,
                FaultInjectionProperties.Mode.FAIL,
                Duration.ZERO));

    assertThatThrownBy(() -> injection.before(FaultPoint.PAYMENT_PROVIDER))
        .isInstanceOf(InjectedFaultException.class);
    assertThatNoException().isThrownBy(() -> injection.before(FaultPoint.OUTBOX_PUBLISH));
  }
}
