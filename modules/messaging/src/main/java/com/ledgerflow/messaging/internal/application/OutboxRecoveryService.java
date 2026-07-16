package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import com.ledgerflow.operations.api.OperationRetryHandler;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OutboxRecoveryService implements OperationRetryHandler {

  private final JdbcOutboxStore outboxStore;
  private final Clock clock;

  public OutboxRecoveryService(JdbcOutboxStore outboxStore, Clock clock) {
    this.outboxStore = outboxStore;
    this.clock = clock;
  }

  @Override
  public String getSupportedOperationType() {
    return "OUTBOX";
  }

  @Override
  public void handleRetry(
      UUID commandId, UUID operationId, String idempotencyKeyHash, boolean isBreakGlass) {
    boolean updated = outboxStore.operatorRetry(operationId, clock.instant());
    if (!updated) {
      throw new IllegalStateException(
          "Outbox event " + operationId + " is not in FAILED state or does not exist");
    }
  }
}
