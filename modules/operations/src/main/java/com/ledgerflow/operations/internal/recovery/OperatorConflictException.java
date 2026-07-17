package com.ledgerflow.operations.internal.recovery;

public final class OperatorConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Code code;

  OperatorConflictException(Code code) {
    this.code = code;
  }

  Code code() {
    return code;
  }

  enum Code {
    IDEMPOTENCY_KEY_REUSED,
    RETRY_ALREADY_ACTIVE,
    RETRY_COOLDOWN,
    RETRY_LIMIT_REACHED,
    BREAK_GLASS_NOT_AVAILABLE,
    BREAK_GLASS_APPROVAL_USED,
    OPERATION_NOT_RETRYABLE
  }
}
