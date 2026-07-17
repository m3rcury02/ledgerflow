package com.ledgerflow.operations.api;

public interface OperationRecoveryHandler {

  OperationType operationType();

  OperationRecoveryResult recover(OperationRecoveryContext context);
}
