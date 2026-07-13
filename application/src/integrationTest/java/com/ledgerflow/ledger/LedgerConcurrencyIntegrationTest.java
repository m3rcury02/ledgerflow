package com.ledgerflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.ledger.api.PostPaymentCaptureCommand;
import com.ledgerflow.ledger.api.PostedJournal;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.testing.ledger.LedgerIntegrationTestSupport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LedgerConcurrencyIntegrationTest extends LedgerIntegrationTestSupport {

  @Test
  void concurrentPostingsForOnePaymentConvergeOnOneJournal() throws Exception {
    Payment confirmed = confirmedPayment();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<PostedJournal> first = executor.submit(() -> postAfterStart(confirmed, ready, start));
      Future<PostedJournal> second = executor.submit(() -> postAfterStart(confirmed, ready, start));

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      PostedJournal firstResult = first.get(30, TimeUnit.SECONDS);
      PostedJournal secondResult = second.get(30, TimeUnit.SECONDS);

      assertThat(firstResult.transactionId()).isEqualTo(secondResult.transactionId());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(transactionCount()).isOne();
      assertThat(entryCount()).isEqualTo(2);
    }
  }

  private PostedJournal postAfterStart(Payment payment, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent ledger test did not start in time");
    }
    return ledgerPosting.postPaymentCapture(
        new PostPaymentCaptureCommand(payment.paymentId(), correlationId(), LEDGER_ACTOR));
  }
}
