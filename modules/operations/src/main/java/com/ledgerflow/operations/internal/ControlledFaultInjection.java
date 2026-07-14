package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.InjectedFaultException;

final class ControlledFaultInjection implements FaultInjection {

  private final FaultInjectionProperties properties;

  ControlledFaultInjection(FaultInjectionProperties properties) {
    this.properties = properties;
  }

  @Override
  public void before(FaultPoint point) {
    if (!properties.enabled() || properties.point() != point) {
      return;
    }
    if (properties.mode() == FaultInjectionProperties.Mode.FAIL) {
      throw new InjectedFaultException(point);
    }
    try {
      Thread.sleep(properties.delay());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Controlled fault delay was interrupted", exception);
    }
  }
}
