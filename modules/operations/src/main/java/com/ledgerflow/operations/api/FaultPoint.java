package com.ledgerflow.operations.api;

/** Explicit allowlist of operations that may be fault-injected in local and test profiles. */
public enum FaultPoint {
  PAYMENT_PROVIDER,
  OUTBOX_PUBLISH,
  NOTIFICATION_CONSUME,
  NOTIFICATION_OFFSET_COMMIT,
  NOTIFICATION_DLT_PUBLISH
}
