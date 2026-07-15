package com.ledgerflow.ledger.internal.application;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.api.PostCorrectionCommand;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.ledger.api.PostedJournal;

final class ObservedLedgerPosting implements LedgerPosting {

  private final LedgerPostingService delegate;
  private final LedgerObservability observability;

  ObservedLedgerPosting(LedgerPostingService delegate, LedgerObservability observability) {
    this.delegate = delegate;
    this.observability = observability;
  }

  @Override
  public PostedJournal postPaymentCapture(PostPaymentCaptureCommand command) {
    try (LedgerObservability.PostingSpan span = observability.startCapturePosting()) {
      try {
        PostedJournal result = delegate.postPaymentCapture(command);
        LedgerObservability.Outcome outcome =
            result.replayed()
                ? LedgerObservability.Outcome.REPLAY
                : LedgerObservability.Outcome.SUCCESS;
        observability.record(outcome);
        span.outcome(outcome);
        return result;
      } catch (LedgerIntegrityException exception) {
        observability.record(LedgerObservability.Outcome.CONFLICT);
        span.outcome(LedgerObservability.Outcome.CONFLICT);
        throw exception;
      } catch (RuntimeException exception) {
        observability.record(LedgerObservability.Outcome.FAILURE);
        span.outcome(LedgerObservability.Outcome.FAILURE);
        throw exception;
      }
    }
  }

  @Override
  public PostedJournal postCorrection(PostCorrectionCommand command) {
    return delegate.postCorrection(command);
  }
}
