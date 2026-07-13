package com.ledgerflow.ledger.api;

public interface LedgerPosting {

  PostedJournal postPaymentCapture(PostPaymentCaptureCommand command);

  PostedJournal postCorrection(PostCorrectionCommand command);
}
