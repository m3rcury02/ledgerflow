package com.ledgerflow.payments.internal.domain;

public enum AttemptOutcome {
  STARTED,
  SUCCEEDED,
  DECLINED,
  TEMPORARY_FAILURE,
  TIMEOUT,
  UNKNOWN,
  NOT_FOUND,
  INVALID_RESPONSE
}
