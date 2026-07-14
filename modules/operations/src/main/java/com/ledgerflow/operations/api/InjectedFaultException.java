package com.ledgerflow.operations.api;

public final class InjectedFaultException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InjectedFaultException(FaultPoint point) {
    super("Controlled fault injected at " + point);
  }
}
