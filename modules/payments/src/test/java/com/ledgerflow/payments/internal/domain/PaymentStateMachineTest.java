package com.ledgerflow.payments.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentStateMachineTest {

  private static final Instant NOW = Instant.parse("2026-07-13T10:42:00Z");

  @Test
  void followsAuthorizationAndProviderCaptureStatesExplicitly() {
    UUID captureRequestId = UUID.randomUUID();

    Payment authorizing = created().startAuthorization(NOW);
    Payment authorized = authorizing.authorizationSucceeded("auth-provider-1", NOW);
    Payment capturing = authorized.startCapture(captureRequestId, NOW);
    Payment confirmed = capturing.captureSucceeded("capture-provider-1", NOW);
    Payment accounted = confirmed.captureAccounted(NOW.plusSeconds(1));
    Payment finalized = accounted.captureFinalized(NOW.plusSeconds(2));

    assertThat(authorizing.state()).isEqualTo(PaymentState.AUTHORIZING);
    assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(authorized.paymentMethodReference()).isNull();
    assertThat(capturing.captureRequestId()).isEqualTo(captureRequestId);
    assertThat(confirmed.state()).isEqualTo(PaymentState.CAPTURE_CONFIRMED);
    assertThat(confirmed.providerCaptureId()).isEqualTo("capture-provider-1");
    assertThat(accounted.state()).isEqualTo(PaymentState.CAPTURE_ACCOUNTED);
    assertThat(finalized.state()).isEqualTo(PaymentState.CAPTURED);
    assertThatThrownBy(() -> confirmed.captureFinalized(NOW.plusSeconds(2)))
        .isInstanceOf(IllegalPaymentTransitionException.class);
  }

  @Test
  void unknownOutcomeCanResumeWithTheSameRequestIdentifier() {
    Payment unknown = created().startAuthorization(NOW).authorizationUnknown("TIMEOUT", NOW);
    Payment resumed = unknown.resumeAuthorization(NOW);

    assertThat(unknown.state()).isEqualTo(PaymentState.AUTHORIZATION_UNKNOWN);
    assertThat(resumed.state()).isEqualTo(PaymentState.AUTHORIZING);
    assertThat(resumed.authorizationRequestId()).isEqualTo(unknown.authorizationRequestId());
  }

  @Test
  void confirmedDeclineIsTerminal() {
    Payment declined = created().startAuthorization(NOW).authorizationDeclined("DECLINED", NOW);

    assertThat(declined.state()).isEqualTo(PaymentState.DECLINED);
    assertThatThrownBy(() -> declined.startAuthorization(NOW))
        .isInstanceOf(IllegalPaymentTransitionException.class)
        .hasMessageContaining("DECLINED")
        .hasMessageContaining("AUTHORIZING");
  }

  @Test
  void captureBeforeAuthorizationIsADomainError() {
    assertThatThrownBy(() -> created().startCapture(UUID.randomUUID(), NOW))
        .isInstanceOf(IllegalPaymentTransitionException.class)
        .hasMessageContaining("CREATED")
        .hasMessageContaining("CAPTURING");
  }

  @Test
  void retryPendingStatesResumeOnlyTheirOwnStage() {
    Payment authorizationPending =
        created().startAuthorization(NOW).authorizationRetryPending("TEMPORARY", NOW);
    Payment capturePending =
        created()
            .startAuthorization(NOW)
            .authorizationSucceeded("auth-provider-1", NOW)
            .startCapture(UUID.randomUUID(), NOW)
            .captureRetryPending("TEMPORARY", NOW);

    assertThat(authorizationPending.resumeAuthorization(NOW).state())
        .isEqualTo(PaymentState.AUTHORIZING);
    assertThat(capturePending.resumeCapture(NOW).state()).isEqualTo(PaymentState.CAPTURING);
    assertThatThrownBy(() -> capturePending.resumeAuthorization(NOW))
        .isInstanceOf(IllegalPaymentTransitionException.class);
  }

  private Payment created() {
    return new Payment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PaymentMoney(25_990, "INR"),
        PaymentState.CREATED,
        null,
        "pm_mock_success",
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        NOW,
        NOW);
  }
}
