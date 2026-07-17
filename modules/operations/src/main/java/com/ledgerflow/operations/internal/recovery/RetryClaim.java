package com.ledgerflow.operations.internal.recovery;

record RetryClaim(RetryCommand command, boolean firstExecution, boolean takeover) {}
