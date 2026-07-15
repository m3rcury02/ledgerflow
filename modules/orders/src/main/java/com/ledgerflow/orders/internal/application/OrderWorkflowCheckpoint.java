package com.ledgerflow.orders.internal.application;

import java.util.UUID;

@FunctionalInterface
public interface OrderWorkflowCheckpoint {

  void afterCaptureAccounting(UUID paymentId);
}
