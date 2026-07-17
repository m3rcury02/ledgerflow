package com.ledgerflow.operations.api;

@FunctionalInterface
public interface RecoveryLeaseGuard {

  void requireCurrent();
}
