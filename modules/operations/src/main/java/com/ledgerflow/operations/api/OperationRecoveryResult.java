package com.ledgerflow.operations.api;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record OperationRecoveryResult(Status status, String code, Duration recheckAfter) {

  private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,64}");

  public OperationRecoveryResult {
    Objects.requireNonNull(status, "status must not be null");
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("recovery result code is invalid");
    }
    if (status == Status.WAITING) {
      if (recheckAfter == null || recheckAfter.isNegative() || recheckAfter.isZero()) {
        throw new IllegalArgumentException("waiting recovery requires a positive recheck delay");
      }
    } else if (recheckAfter != null) {
      throw new IllegalArgumentException("terminal recovery result cannot have a recheck delay");
    }
  }

  public static OperationRecoveryResult completed(String code) {
    return new OperationRecoveryResult(Status.COMPLETED, code, null);
  }

  public static OperationRecoveryResult waiting(String code, Duration recheckAfter) {
    return new OperationRecoveryResult(Status.WAITING, code, recheckAfter);
  }

  public static OperationRecoveryResult failed(String code) {
    return new OperationRecoveryResult(Status.FAILED, code, null);
  }

  public enum Status {
    COMPLETED,
    WAITING,
    FAILED
  }
}
