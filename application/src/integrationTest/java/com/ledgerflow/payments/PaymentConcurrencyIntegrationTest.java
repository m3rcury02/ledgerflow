package com.ledgerflow.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.payments.internal.application.ConcurrentPaymentModificationException;
import com.ledgerflow.payments.internal.domain.IllegalPaymentTransitionException;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PaymentConcurrencyIntegrationTest extends PaymentIntegrationTestSupport {

  @Test
  void optimisticLockingPreventsConcurrentAuthorizationStateCorruption() throws Exception {
    Payment created = createPayment("pm_mock_slow_response");

    List<Object> results =
        concurrently(
            () -> paymentWorkflow.authorize(created.paymentId(), correlationId()),
            () -> paymentWorkflow.authorize(created.paymentId(), correlationId()));

    assertOneSuccessfulResultAndOneConcurrencyLoser(results);
    assertThat(paymentWorkflow.get(created.paymentId()).state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
  }

  @Test
  void optimisticLockingPreventsConcurrentCaptureStateCorruption() throws Exception {
    Payment authorized = authorize("pm_mock_slow_response");

    List<Object> results =
        concurrently(
            () -> paymentWorkflow.capture(authorized.paymentId(), correlationId()),
            () -> paymentWorkflow.capture(authorized.paymentId(), correlationId()));

    assertOneSuccessfulResultAndOneConcurrencyLoser(results);
    Payment captured = paymentWorkflow.get(authorized.paymentId());
    assertThat(captured.state()).isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(PROVIDER.callCount("CAPTURE", captured.captureRequestId())).isOne();
  }

  private List<Object> concurrently(Callable<Payment> first, Callable<Payment> second)
      throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(
              executor.submit(awaitStart(first, ready, start)),
              executor.submit(awaitStart(second, ready, start)));
      ready.await();
      start.countDown();
      List<Object> results = new ArrayList<>();
      for (var future : futures) {
        try {
          results.add(future.get());
        } catch (ExecutionException exception) {
          results.add(exception.getCause());
        }
      }
      return results;
    }
  }

  private Callable<Payment> awaitStart(
      Callable<Payment> task, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return task.call();
    };
  }

  private void assertOneSuccessfulResultAndOneConcurrencyLoser(List<Object> results) {
    assertThat(results).filteredOn(Payment.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(
            result ->
                result instanceof ConcurrentPaymentModificationException
                    || result instanceof IllegalPaymentTransitionException)
        .hasSize(1);
  }
}
