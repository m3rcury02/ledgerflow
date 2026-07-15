package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.AttemptActivity;
import com.ledgerflow.payments.internal.domain.AttemptHistory;
import com.ledgerflow.payments.internal.domain.AttemptOutcome;
import com.ledgerflow.payments.internal.domain.IllegalPaymentTransitionException;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.payments.internal.domain.PaymentState;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

public class PaymentWorkflowService {

  private final PaymentStore paymentStore;
  private final PaymentProvider paymentProvider;
  private final PaymentRetryPolicy retryPolicy;
  private final ProviderRetryClassifier retryClassifier;
  private final Clock clock;
  private final Supplier<UUID> requestIdSupplier;
  private final PaymentMetrics metrics;

  public PaymentWorkflowService(
      PaymentStore paymentStore,
      PaymentProvider paymentProvider,
      PaymentRetryPolicy retryPolicy,
      ProviderRetryClassifier retryClassifier,
      Clock clock,
      Supplier<UUID> requestIdSupplier,
      PaymentMetrics metrics) {
    this.paymentStore = paymentStore;
    this.paymentProvider = paymentProvider;
    this.retryPolicy = retryPolicy;
    this.retryClassifier = retryClassifier;
    this.clock = clock;
    this.requestIdSupplier = requestIdSupplier;
    this.metrics = metrics;
  }

  public Payment create(CreatePaymentCommand command) {
    validatePaymentMethodReference(command.paymentMethodReference());
    Payment created = paymentStore.create(command, clock.instant());
    metrics.recordStateAfterCommit(created.state());
    return created;
  }

  public Payment authorize(UUID paymentId, String correlationId) {
    validateCorrelationId(correlationId);
    Payment current = get(paymentId);
    if (current.state() == PaymentState.AUTHORIZED || current.state() == PaymentState.DECLINED) {
      return current;
    }
    if (current.state() == PaymentState.AUTHORIZING
        || current.state() == PaymentState.AUTHORIZATION_UNKNOWN) {
      return recover(paymentId, correlationId);
    }
    if (current.state() == PaymentState.AUTHORIZATION_RETRY_PENDING) {
      Payment active = paymentStore.save(current, current.resumeAuthorization(clock.instant()));
      metrics.recordStateAfterCommit(active.state());
      return execute(active, PaymentStage.AUTHORIZATION, correlationId);
    }
    Payment active = paymentStore.save(current, current.startAuthorization(clock.instant()));
    metrics.recordStateAfterCommit(active.state());
    return execute(active, PaymentStage.AUTHORIZATION, correlationId);
  }

  public Payment capture(UUID paymentId, String correlationId) {
    validateCorrelationId(correlationId);
    Payment current = get(paymentId);
    if (current.state() == PaymentState.CAPTURE_CONFIRMED
        || current.state() == PaymentState.CAPTURE_ACCOUNTED
        || current.state() == PaymentState.CAPTURED
        || current.state() == PaymentState.CAPTURE_DECLINED) {
      return current;
    }
    if (current.state() == PaymentState.CAPTURING
        || current.state() == PaymentState.CAPTURE_UNKNOWN) {
      return recover(paymentId, correlationId);
    }
    if (current.state() == PaymentState.CAPTURE_RETRY_PENDING) {
      Payment active = paymentStore.save(current, current.resumeCapture(clock.instant()));
      metrics.recordStateAfterCommit(active.state());
      return execute(active, PaymentStage.CAPTURE, correlationId);
    }
    Payment active =
        paymentStore.save(current, current.startCapture(requestIdSupplier.get(), clock.instant()));
    metrics.recordStateAfterCommit(active.state());
    return execute(active, PaymentStage.CAPTURE, correlationId);
  }

  public Payment recover(UUID paymentId, String correlationId) {
    validateCorrelationId(correlationId);
    Payment current = get(paymentId);
    PaymentStage stage = recoveryStage(current.state());
    int attemptNumber = Math.max(1, current.attemptCount(stage));
    paymentStore.appendHistory(
        current.paymentId(),
        history(
            current,
            stage,
            AttemptActivity.LOOKUP,
            attemptNumber,
            AttemptOutcome.STARTED,
            null,
            null,
            correlationId));

    ProviderLookupResult lookup =
        paymentProvider.lookup(stage, current.requestId(stage), correlationId);
    return applyLookup(current, stage, attemptNumber, lookup, correlationId);
  }

  public Payment get(UUID paymentId) {
    return paymentStore.find(paymentId).orElseThrow(PaymentNotFoundException::new);
  }

  private Payment execute(Payment initial, PaymentStage stage, String correlationId) {
    Payment current = initial;
    while (current.attemptCount(stage) < retryPolicy.maxAttempts()) {
      StartedAttempt started =
          paymentStore.startAttempt(current, stage, correlationId, clock.instant());
      current = started.payment();
      ProviderResult result = callProvider(current, stage, correlationId);

      if (result instanceof ProviderResult.Success success) {
        return persistSuccess(
            current, stage, started.attemptNumber(), success.providerReference(), correlationId);
      }
      if (result instanceof ProviderResult.Declined declined) {
        return persistDecline(
            current, stage, started.attemptNumber(), declined.failureCode(), correlationId);
      }
      if (result instanceof ProviderResult.Unknown unknown) {
        return persistUnknown(
            current, stage, started.attemptNumber(), unknown.failureCode(), correlationId);
      }
      if (result instanceof ProviderResult.InvalidResponse invalid) {
        return persistInvalid(
            current, stage, started.attemptNumber(), invalid.failureCode(), correlationId);
      }
      if (!retryClassifier.isRetryable(result)) {
        throw new IllegalStateException("Payment provider result has no explicit classification");
      }
      ProviderResult.TemporaryFailure temporary = (ProviderResult.TemporaryFailure) result;
      paymentStore.appendHistory(
          current.paymentId(),
          history(
              current,
              stage,
              AttemptActivity.CALL,
              started.attemptNumber(),
              AttemptOutcome.TEMPORARY_FAILURE,
              null,
              temporary.failureCode(),
              correlationId));
      if (started.attemptNumber() >= retryPolicy.maxAttempts()) {
        return persistRetryPending(current, stage, temporary.failureCode());
      }
      retryPolicy.pauseAfterAttempt(started.attemptNumber());
      current = get(current.paymentId());
    }
    return persistRetryPending(current, stage, "PROVIDER_TEMPORARY_FAILURE");
  }

  private Payment applyLookup(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      ProviderLookupResult lookup,
      String correlationId) {
    if (lookup instanceof ProviderLookupResult.FoundSuccess success) {
      return persistSuccess(
          current,
          stage,
          attemptNumber,
          success.providerReference(),
          AttemptActivity.LOOKUP,
          correlationId);
    }
    if (lookup instanceof ProviderLookupResult.FoundDecline decline) {
      return persistDecline(
          current,
          stage,
          attemptNumber,
          decline.failureCode(),
          AttemptActivity.LOOKUP,
          correlationId);
    }
    if (lookup instanceof ProviderLookupResult.NotFound) {
      metrics.record(stage, PaymentMetrics.Outcome.NOT_FOUND);
      paymentStore.appendHistory(
          current.paymentId(),
          history(
              current,
              stage,
              AttemptActivity.LOOKUP,
              attemptNumber,
              AttemptOutcome.NOT_FOUND,
              null,
              "PROVIDER_OPERATION_NOT_FOUND",
              correlationId));
      Payment active = resumeIfUnknown(current, stage);
      return execute(active, stage, correlationId);
    }
    if (lookup instanceof ProviderLookupResult.TemporarilyUnavailable unavailable) {
      AttemptHistory history =
          history(
              current,
              stage,
              AttemptActivity.LOOKUP,
              attemptNumber,
              AttemptOutcome.TEMPORARY_FAILURE,
              null,
              unavailable.failureCode(),
              correlationId);
      if (isActive(current.state(), stage)) {
        Payment persisted =
            paymentStore.saveWithHistory(
                current, unknown(current, stage, unavailable.failureCode()), history);
        metrics.record(stage, PaymentMetrics.Outcome.UNKNOWN);
        metrics.recordStateAfterCommit(persisted.state());
        return persisted;
      }
      paymentStore.appendHistory(current.paymentId(), history);
      metrics.record(stage, PaymentMetrics.Outcome.UNKNOWN);
      return current;
    }
    ProviderLookupResult.InvalidResponse invalid = (ProviderLookupResult.InvalidResponse) lookup;
    Payment persisted =
        paymentStore.saveWithHistory(
            current,
            current.failed(invalid.failureCode(), clock.instant()),
            history(
                current,
                stage,
                AttemptActivity.LOOKUP,
                attemptNumber,
                AttemptOutcome.INVALID_RESPONSE,
                null,
                invalid.failureCode(),
                correlationId));
    metrics.record(stage, PaymentMetrics.Outcome.INVALID);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private ProviderResult callProvider(Payment payment, PaymentStage stage, String correlationId) {
    return switch (stage) {
      case AUTHORIZATION ->
          paymentProvider.authorize(
              new PaymentProvider.AuthorizationRequest(
                  payment.authorizationRequestId(),
                  payment.paymentId(),
                  payment.orderId(),
                  payment.amount(),
                  payment.paymentMethodReference(),
                  correlationId));
      case CAPTURE ->
          paymentProvider.capture(
              new PaymentProvider.CaptureRequest(
                  payment.captureRequestId(),
                  payment.paymentId(),
                  payment.orderId(),
                  payment.amount(),
                  payment.providerAuthorizationId(),
                  correlationId));
    };
  }

  private Payment persistSuccess(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String providerReference,
      String correlationId) {
    return persistSuccess(
        current, stage, attemptNumber, providerReference, AttemptActivity.CALL, correlationId);
  }

  private Payment persistSuccess(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String providerReference,
      AttemptActivity activity,
      String correlationId) {
    Payment updated =
        switch (stage) {
          case AUTHORIZATION -> current.authorizationSucceeded(providerReference, clock.instant());
          case CAPTURE -> current.captureSucceeded(providerReference, clock.instant());
        };
    Payment persisted =
        paymentStore.saveWithHistory(
            current,
            updated,
            history(
                current,
                stage,
                activity,
                attemptNumber,
                AttemptOutcome.SUCCEEDED,
                providerReference,
                null,
                correlationId));
    metrics.record(stage, PaymentMetrics.Outcome.SUCCESS);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private Payment persistDecline(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String failureCode,
      String correlationId) {
    return persistDecline(
        current, stage, attemptNumber, failureCode, AttemptActivity.CALL, correlationId);
  }

  private Payment persistDecline(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String failureCode,
      AttemptActivity activity,
      String correlationId) {
    Payment updated =
        switch (stage) {
          case AUTHORIZATION -> current.authorizationDeclined(failureCode, clock.instant());
          case CAPTURE -> current.captureDeclined(failureCode, clock.instant());
        };
    Payment persisted =
        paymentStore.saveWithHistory(
            current,
            updated,
            history(
                current,
                stage,
                activity,
                attemptNumber,
                AttemptOutcome.DECLINED,
                null,
                failureCode,
                correlationId));
    metrics.record(stage, PaymentMetrics.Outcome.DECLINE);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private Payment persistUnknown(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String failureCode,
      String correlationId) {
    AttemptOutcome outcome =
        "PROVIDER_TIMEOUT".equals(failureCode) ? AttemptOutcome.TIMEOUT : AttemptOutcome.UNKNOWN;
    Payment persisted =
        paymentStore.saveWithHistory(
            current,
            unknown(current, stage, failureCode),
            history(
                current,
                stage,
                AttemptActivity.CALL,
                attemptNumber,
                outcome,
                null,
                failureCode,
                correlationId));
    metrics.record(stage, PaymentMetrics.Outcome.UNKNOWN);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private Payment persistInvalid(
      Payment current,
      PaymentStage stage,
      int attemptNumber,
      String failureCode,
      String correlationId) {
    Payment persisted =
        paymentStore.saveWithHistory(
            current,
            current.failed(failureCode, clock.instant()),
            history(
                current,
                stage,
                AttemptActivity.CALL,
                attemptNumber,
                AttemptOutcome.INVALID_RESPONSE,
                null,
                failureCode,
                correlationId));
    metrics.record(stage, PaymentMetrics.Outcome.INVALID);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private Payment persistRetryPending(Payment current, PaymentStage stage, String failureCode) {
    Payment updated =
        switch (stage) {
          case AUTHORIZATION -> current.authorizationRetryPending(failureCode, clock.instant());
          case CAPTURE -> current.captureRetryPending(failureCode, clock.instant());
        };
    Payment persisted = paymentStore.save(current, updated);
    metrics.record(stage, PaymentMetrics.Outcome.RETRY_PENDING);
    metrics.recordStateAfterCommit(persisted.state());
    return persisted;
  }

  private Payment unknown(Payment payment, PaymentStage stage, String failureCode) {
    return switch (stage) {
      case AUTHORIZATION -> payment.authorizationUnknown(failureCode, clock.instant());
      case CAPTURE -> payment.captureUnknown(failureCode, clock.instant());
    };
  }

  private Payment resumeIfUnknown(Payment payment, PaymentStage stage) {
    return switch (stage) {
      case AUTHORIZATION ->
          payment.state() == PaymentState.AUTHORIZATION_UNKNOWN
              ? recordResumed(
                  paymentStore.save(payment, payment.resumeAuthorization(clock.instant())))
              : payment;
      case CAPTURE ->
          payment.state() == PaymentState.CAPTURE_UNKNOWN
              ? recordResumed(paymentStore.save(payment, payment.resumeCapture(clock.instant())))
              : payment;
    };
  }

  private Payment recordResumed(Payment payment) {
    metrics.recordStateAfterCommit(payment.state());
    return payment;
  }

  private AttemptHistory history(
      Payment payment,
      PaymentStage stage,
      AttemptActivity activity,
      int attemptNumber,
      AttemptOutcome outcome,
      String providerReference,
      String failureCode,
      String correlationId) {
    return new AttemptHistory(
        stage,
        activity,
        attemptNumber,
        outcome,
        payment.requestId(stage),
        providerReference,
        failureCode,
        correlationId,
        clock.instant());
  }

  private PaymentStage recoveryStage(PaymentState state) {
    return switch (state) {
      case AUTHORIZING, AUTHORIZATION_UNKNOWN -> PaymentStage.AUTHORIZATION;
      case CAPTURING, CAPTURE_UNKNOWN -> PaymentStage.CAPTURE;
      default -> throw new IllegalPaymentTransitionException(state, state);
    };
  }

  private boolean isActive(PaymentState state, PaymentStage stage) {
    return switch (stage) {
      case AUTHORIZATION -> state == PaymentState.AUTHORIZING;
      case CAPTURE -> state == PaymentState.CAPTURING;
    };
  }

  private void validatePaymentMethodReference(String reference) {
    if (reference == null || !reference.matches("pm_mock_[a-z_]+") || reference.length() > 128) {
      throw new IllegalArgumentException("payment method reference is invalid");
    }
  }

  private void validateCorrelationId(String correlationId) {
    if (correlationId == null
        || correlationId.length() > 64
        || !correlationId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("correlation ID is invalid");
    }
  }
}
