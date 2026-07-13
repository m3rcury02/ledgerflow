package com.ledgerflow.testing.ledger;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

public abstract class LedgerIntegrationTestSupport extends PaymentIntegrationTestSupport {

  protected static final String LEDGER_ACTOR = "ledger-test";

  @Autowired protected LedgerPosting ledgerPosting;
  @Autowired protected TransactionTemplate transactionTemplate;

  protected Payment confirmedPayment() {
    Payment authorized = authorize("pm_mock_success");
    return paymentWorkflow.capture(authorized.paymentId(), correlationId());
  }

  protected PostedJournal postCapture(Payment payment) {
    return ledgerPosting.postPaymentCapture(
        new PostPaymentCaptureCommand(payment.paymentId(), correlationId(), LEDGER_ACTOR));
  }

  protected long transactionCount() {
    return jdbcClient.sql("SELECT count(*) FROM ledger_transactions").query(Long.class).single();
  }

  protected long entryCount() {
    return jdbcClient.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single();
  }
}
