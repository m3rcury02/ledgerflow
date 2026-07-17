package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import java.time.Clock;

final class OutboxOperationRecoveryHandler implements OperationRecoveryHandler {

  private final JdbcOutboxStore store;
  private final Clock clock;

  OutboxOperationRecoveryHandler(JdbcOutboxStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public OperationType operationType() {
    return OperationType.OUTBOX;
  }

  @Override
  public OperationRecoveryResult recover(OperationRecoveryContext context) {
    context.leaseGuard().requireCurrent();
    String status = store.status(context.sourceId()).orElse(null);
    if ("PUBLISHED".equals(status)) {
      return OperationRecoveryResult.completed("OUTBOX_PUBLISHED");
    }
    if ("FAILED".equals(status)) {
      if (!context.firstExecution()) {
        return OperationRecoveryResult.failed("OUTBOX_REPUBLISH_FAILED");
      }
      context.leaseGuard().requireCurrent();
      store.resetFailedForOperator(context.sourceId(), clock.instant(), context.leaseGuard());
    } else if (status == null) {
      return OperationRecoveryResult.failed("OUTBOX_NOT_FOUND");
    }
    return OperationRecoveryResult.waiting("OUTBOX_PUBLICATION_PENDING", context.recheckDelay());
  }
}
