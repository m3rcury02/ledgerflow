package com.ledgerflow.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.application.StartedAttempt;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.payments.internal.domain.PaymentState;
import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentRecoveryIntegrationTest extends PaymentIntegrationTestSupport {

  @Test
  void reconcilesAnUnknownAuthorizationOutcomeBeforeAnyResend() {
    Payment created = createPayment("pm_mock_authorization_timeout");

    Payment unknown = paymentWorkflow.authorize(created.paymentId(), correlationId());
    Payment recovered = paymentWorkflow.recover(created.paymentId(), correlationId());

    assertThat(unknown.state()).isEqualTo(PaymentState.AUTHORIZATION_UNKNOWN);
    assertThat(unknown.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(recovered.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(recovered.authorizationRequestId()).isEqualTo(created.authorizationRequestId());
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
  }

  @Test
  void reconcilesAnUnknownCaptureOutcomeBeforeAnyResend() {
    Payment authorized = authorize("pm_mock_capture_timeout");

    Payment unknown = paymentWorkflow.capture(authorized.paymentId(), correlationId());
    Payment recovered = paymentWorkflow.recover(authorized.paymentId(), correlationId());

    assertThat(unknown.state()).isEqualTo(PaymentState.CAPTURE_UNKNOWN);
    assertThat(recovered.state()).isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(PROVIDER.callCount("CAPTURE", unknown.captureRequestId())).isOne();
  }

  @Test
  void recoversWhenTheProcessCrashesAfterProviderSuccessBeforeLocalPersistence() {
    Payment created = createPayment("pm_mock_success");
    Payment authorizing = paymentStore.save(created, created.startAuthorization(Instant.now()));
    StartedAttempt started =
        paymentStore.startAttempt(
            authorizing, PaymentStage.AUTHORIZATION, correlationId(), Instant.now());

    ProviderResult providerResult =
        paymentProvider.authorize(
            new com.ledgerflow.payments.internal.application.PaymentProvider.AuthorizationRequest(
                started.payment().authorizationRequestId(),
                started.payment().paymentId(),
                started.payment().orderId(),
                started.payment().amount(),
                started.payment().paymentMethodReference(),
                correlationId()));

    assertThat(providerResult).isInstanceOf(ProviderResult.Success.class);
    assertThat(paymentWorkflow.get(created.paymentId()).state())
        .isEqualTo(PaymentState.AUTHORIZING);

    Payment recovered = paymentWorkflow.recover(created.paymentId(), correlationId());

    assertThat(recovered.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(PROVIDER.callCount("AUTHORIZATION", created.authorizationRequestId())).isOne();
  }
}
