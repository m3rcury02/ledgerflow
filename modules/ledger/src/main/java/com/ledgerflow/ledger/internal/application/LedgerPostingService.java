package com.ledgerflow.ledger.internal.application;

import com.ledgerflow.ledger.api.PostCorrectionCommand;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.ledger.internal.domain.JournalEntry;
import com.ledgerflow.ledger.internal.domain.JournalPosting;
import com.ledgerflow.ledger.internal.domain.JournalType;
import com.ledgerflow.ledger.internal.persistence.JdbcLedgerStore;
import com.ledgerflow.ledger.internal.persistence.StoredJournal;
import com.ledgerflow.messaging.api.AppendPaymentCapturedEvent;
import com.ledgerflow.messaging.api.OutboxEventAppender;
import com.ledgerflow.payments.api.CaptureAccountingStatus;
import com.ledgerflow.payments.api.CapturedPayment;
import com.ledgerflow.payments.api.PaymentAccounting;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class LedgerPostingService {

  private final JdbcLedgerStore ledgerStore;
  private final PaymentAccounting paymentAccounting;
  private final OutboxEventAppender outboxEventAppender;
  private final Clock clock;

  public LedgerPostingService(
      JdbcLedgerStore ledgerStore,
      PaymentAccounting paymentAccounting,
      OutboxEventAppender outboxEventAppender,
      Clock clock) {
    this.ledgerStore = ledgerStore;
    this.paymentAccounting = paymentAccounting;
    this.outboxEventAppender = outboxEventAppender;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PostedJournal postPaymentCapture(PostPaymentCaptureCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    CapturedPayment payment = paymentAccounting.lockCapture(command.paymentId());
    Optional<StoredJournal> existing = ledgerStore.findPaymentCapture(payment.paymentId());
    if (payment.accountingStatus() == CaptureAccountingStatus.ACCOUNTED) {
      PostedJournal replay = replayExisting(payment, existing);
      String originalCorrelationId = existing.orElseThrow().posting().correlationId();
      appendOutbox(payment, replay, originalCorrelationId);
      return replay;
    }
    if (existing.isPresent()) {
      throw new LedgerIntegrityException(
          "Payment is confirmed but already has a journal transaction");
    }

    JournalPosting posting =
        JournalPosting.paymentCapture(
            payment.paymentId(),
            payment.orderId(),
            payment.amountMinor(),
            payment.currency(),
            command.correlationId(),
            command.actor());
    Instant now = clock.instant();
    StoredJournal stored = ledgerStore.insert(posting, now);
    paymentAccounting.markCaptureAccounted(payment.paymentId(), payment.version(), now);
    PostedJournal result = view(stored, false);
    appendOutbox(payment, result, command.correlationId());
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PostedJournal postCorrection(PostCorrectionCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    validateCorrectionReason(command.reason());
    StoredJournal original = ledgerStore.lock(command.originalTransactionId());
    if (original.posting().journalType() != JournalType.PAYMENT_CAPTURE) {
      throw new IllegalArgumentException("Only a payment-capture journal can be corrected");
    }
    Optional<StoredJournal> existing = ledgerStore.findCorrection(original.transactionId());
    if (existing.isPresent()) {
      String expectedDescription = correctionDescription(command.reason());
      if (!expectedDescription.equals(existing.get().posting().description())) {
        throw new LedgerIntegrityException(
            "Correction already exists with different audit metadata");
      }
      return view(existing.get(), true);
    }

    List<JournalEntry> reversed =
        original.posting().entries().stream().map(JournalEntry::reverse).toList();
    JournalPosting correction =
        new JournalPosting(
            JournalType.CORRECTION,
            "LEDGER_CORRECTION",
            original.transactionId(),
            original.posting().paymentId(),
            original.posting().orderId(),
            original.posting().currency(),
            original.transactionId(),
            correctionDescription(command.reason()),
            command.correlationId(),
            command.actor(),
            reversed);
    return view(ledgerStore.insert(correction, clock.instant()), false);
  }

  private PostedJournal replayExisting(CapturedPayment payment, Optional<StoredJournal> existing) {
    StoredJournal stored =
        existing.orElseThrow(
            () ->
                new LedgerIntegrityException(
                    "Accounted payment is missing its journal transaction"));
    JournalPosting posting = stored.posting();
    if (!posting.paymentId().equals(payment.paymentId())
        || !posting.orderId().equals(payment.orderId())
        || !posting.currency().equals(payment.currency())
        || posting.debitTotalMinor() != payment.amountMinor()) {
      throw new LedgerIntegrityException("Existing payment journal does not match the payment");
    }
    return view(stored, true);
  }

  private PostedJournal view(StoredJournal stored, boolean replayed) {
    JournalPosting posting = stored.posting();
    return new PostedJournal(
        stored.transactionId(),
        posting.paymentId(),
        posting.orderId(),
        posting.debitTotalMinor(),
        posting.currency(),
        posting.entries().size(),
        stored.postedAt(),
        posting.reversesTransactionId(),
        replayed);
  }

  private void appendOutbox(CapturedPayment payment, PostedJournal journal, String correlationId) {
    outboxEventAppender.appendPaymentCaptured(
        new AppendPaymentCapturedEvent(
            payment.paymentId(),
            payment.orderId(),
            journal.transactionId(),
            payment.amountMinor(),
            payment.currency(),
            payment.captureRequestId(),
            correlationId,
            journal.postedAt()));
  }

  private void validateCorrectionReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() < 10 || reason.length() > 180) {
      throw new IllegalArgumentException("correction reason must contain 10 to 180 characters");
    }
  }

  private String correctionDescription(String reason) {
    return "Correction: " + reason;
  }
}
