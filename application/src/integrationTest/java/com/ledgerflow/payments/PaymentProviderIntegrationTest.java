package com.ledgerflow.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PaymentProviderIntegrationTest extends PaymentIntegrationTestSupport {

  @Test
  void authorizesAndCapturesWithIndependentStableProviderRequestIds() {
    Payment created = createPayment("pm_mock_success");

    Payment authorized = paymentWorkflow.authorize(created.paymentId(), correlationId());
    Payment authorizationReplay = paymentWorkflow.authorize(created.paymentId(), correlationId());
    Payment captured = paymentWorkflow.capture(created.paymentId(), correlationId());
    Payment captureReplay = paymentWorkflow.capture(created.paymentId(), correlationId());

    assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(authorizationReplay.providerAuthorizationId())
        .isEqualTo(authorized.providerAuthorizationId());
    assertThat(captured.state()).isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(captureReplay.providerCaptureId()).isEqualTo(captured.providerCaptureId());
    assertThat(captured.authorizationRequestId()).isNotEqualTo(captured.captureRequestId());
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captured.captureRequestId())).isOne();
    assertThat(historyCount(created.paymentId())).isEqualTo(4);
  }

  @Test
  void neverRetriesAConfirmedAuthorizationDecline() {
    Payment created = createPayment("pm_mock_authorization_decline");

    Payment declined = paymentWorkflow.authorize(created.paymentId(), correlationId());
    Payment replay = paymentWorkflow.authorize(created.paymentId(), correlationId());

    assertThat(declined.state()).isEqualTo(PaymentState.DECLINED);
    assertThat(replay.failureCode()).isEqualTo("AUTHORIZATION_DECLINED");
    assertThat(declined.authorizationAttemptCount()).isOne();
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
  }

  @Test
  void retriesOnlyATemporaryAuthorizationFailureWithinTheConfiguredBound() {
    Payment created = createPayment("pm_mock_authorization_temporary_error");

    Payment authorized = paymentWorkflow.authorize(created.paymentId(), correlationId());

    assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(authorized.authorizationAttemptCount()).isEqualTo(2);
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isEqualTo(2);
    assertThat(outcomes(created.paymentId()))
        .containsExactly("STARTED", "TEMPORARY_FAILURE", "STARTED", "SUCCEEDED");
  }

  @Test
  void failsClosedOnAContradictoryProviderResponse() {
    Payment created = createPayment("pm_mock_invalid_response");

    Payment failed = paymentWorkflow.authorize(created.paymentId(), correlationId());

    assertThat(failed.state()).isEqualTo(PaymentState.FAILED);
    assertThat(failed.failureCode()).isEqualTo("PROVIDER_RESPONSE_CONTRADICTORY");
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
  }

  @Test
  void retriesATemporaryCaptureFailureButNotAConfirmedCaptureDecline() {
    Payment temporary = authorize("pm_mock_capture_temporary_error");
    Payment captured = paymentWorkflow.capture(temporary.paymentId(), correlationId());

    Payment decline = authorize("pm_mock_capture_decline");
    Payment captureDeclined = paymentWorkflow.capture(decline.paymentId(), correlationId());
    Payment replay = paymentWorkflow.capture(decline.paymentId(), correlationId());

    assertThat(captured.state()).isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(captured.captureAttemptCount()).isEqualTo(2);
    assertThat(PROVIDER.callCount("CAPTURE", captured.captureRequestId())).isEqualTo(2);
    assertThat(captureDeclined.state()).isEqualTo(PaymentState.CAPTURE_DECLINED);
    assertThat(replay.failureCode()).isEqualTo("CAPTURE_DECLINED");
    assertThat(captureDeclined.captureAttemptCount()).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureDeclined.captureRequestId())).isOne();
  }

  @Test
  void doesNotHoldADatabaseTransactionOpenDuringASlowProviderCall() throws Exception {
    Payment created = createPayment("pm_mock_slow_response");

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Payment> future =
          executor.submit(() -> paymentWorkflow.authorize(created.paymentId(), correlationId()));

      assertThat(awaitSlowProviderCall()).isTrue();
      Integer idleTransactions =
          jdbcClient
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_stat_activity
                  WHERE datname = current_database()
                    AND state = 'idle in transaction'
                    AND pid <> pg_backend_pid()
                  """)
              .query(Integer.class)
              .single();

      assertThat(idleTransactions).isZero();
      assertThat(future.get().state()).isEqualTo(PaymentState.AUTHORIZED);
    }
  }

  private int historyCount(UUID paymentId) {
    return jdbcClient
        .sql("SELECT count(*) FROM payment_attempt_history WHERE payment_id = :paymentId")
        .param("paymentId", paymentId)
        .query(Integer.class)
        .single();
  }

  private java.util.List<String> outcomes(UUID paymentId) {
    return jdbcClient
        .sql(
            """
            SELECT outcome
            FROM payment_attempt_history
            WHERE payment_id = :paymentId
            ORDER BY recorded_at, id
            """)
        .param("paymentId", paymentId)
        .query(String.class)
        .list();
  }
}
