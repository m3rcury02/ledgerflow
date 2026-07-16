package com.ledgerflow.operations.api;

import java.util.UUID;

public interface OperationRetryHandler {

  String getSupportedOperationType();

  void handleRetry(
      UUID commandId, UUID operationId, String idempotencyKeyHash, boolean isBreakGlass);
}
