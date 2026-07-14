package com.ledgerflow.payments.internal.application;

/** Central retry classification: only an explicit temporary failure is safe to retry. */
public final class ProviderRetryClassifier {

  public boolean isRetryable(ProviderResult result) {
    return result instanceof ProviderResult.TemporaryFailure;
  }
}
