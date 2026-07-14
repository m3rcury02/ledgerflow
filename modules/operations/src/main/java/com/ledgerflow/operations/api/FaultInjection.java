package com.ledgerflow.operations.api;

/** Local/test-only controlled failure hook. Production receives a no-op implementation. */
@FunctionalInterface
public interface FaultInjection {

  void before(FaultPoint point);
}
