package com.ledgerflow.payments.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Payment(
    UUID paymentId,
    UUID orderId,
    PaymentMoney amount,
    PaymentState state,
    PaymentStage resumeStage,
    String paymentMethodReference,
    UUID authorizationRequestId,
    UUID captureRequestId,
    String providerAuthorizationId,
    String providerCaptureId,
    String failureCode,
    int authorizationAttemptCount,
    int captureAttemptCount,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public Payment {
    Objects.requireNonNull(paymentId, "paymentId must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(authorizationRequestId, "authorizationRequestId must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (authorizationAttemptCount < 0 || captureAttemptCount < 0 || version < 0) {
      throw new IllegalArgumentException("attempt counts and version must not be negative");
    }
  }

  public Payment startAuthorization(Instant now) {
    requireTransition(PaymentState.AUTHORIZING, PaymentState.CREATED);
    return copy(
        PaymentState.AUTHORIZING,
        PaymentStage.AUTHORIZATION,
        paymentMethodReference,
        captureRequestId,
        providerAuthorizationId,
        providerCaptureId,
        null,
        now);
  }

  public Payment resumeAuthorization(Instant now) {
    requireTransition(
        PaymentState.AUTHORIZING,
        PaymentState.AUTHORIZATION_UNKNOWN,
        PaymentState.AUTHORIZATION_RETRY_PENDING);
    return copy(
        PaymentState.AUTHORIZING,
        PaymentStage.AUTHORIZATION,
        paymentMethodReference,
        null,
        null,
        null,
        null,
        now);
  }

  public Payment authorizationSucceeded(String providerReference, Instant now) {
    requireTransition(
        PaymentState.AUTHORIZED, PaymentState.AUTHORIZING, PaymentState.AUTHORIZATION_UNKNOWN);
    requireReference(providerReference);
    return copy(PaymentState.AUTHORIZED, null, null, null, providerReference, null, null, now);
  }

  public Payment authorizationDeclined(String code, Instant now) {
    requireTransition(
        PaymentState.DECLINED, PaymentState.AUTHORIZING, PaymentState.AUTHORIZATION_UNKNOWN);
    requireCode(code);
    return copy(PaymentState.DECLINED, null, null, null, null, null, code, now);
  }

  public Payment authorizationUnknown(String code, Instant now) {
    requireTransition(PaymentState.AUTHORIZATION_UNKNOWN, PaymentState.AUTHORIZING);
    requireCode(code);
    return copy(
        PaymentState.AUTHORIZATION_UNKNOWN,
        PaymentStage.AUTHORIZATION,
        paymentMethodReference,
        null,
        null,
        null,
        code,
        now);
  }

  public Payment authorizationRetryPending(String code, Instant now) {
    requireTransition(PaymentState.AUTHORIZATION_RETRY_PENDING, PaymentState.AUTHORIZING);
    requireCode(code);
    return copy(
        PaymentState.AUTHORIZATION_RETRY_PENDING,
        PaymentStage.AUTHORIZATION,
        paymentMethodReference,
        null,
        null,
        null,
        code,
        now);
  }

  public Payment startCapture(UUID requestId, Instant now) {
    requireTransition(PaymentState.CAPTURING, PaymentState.AUTHORIZED);
    Objects.requireNonNull(requestId, "capture request ID must not be null");
    return copy(
        PaymentState.CAPTURING,
        PaymentStage.CAPTURE,
        null,
        requestId,
        providerAuthorizationId,
        null,
        null,
        now);
  }

  public Payment resumeCapture(Instant now) {
    requireTransition(
        PaymentState.CAPTURING, PaymentState.CAPTURE_UNKNOWN, PaymentState.CAPTURE_RETRY_PENDING);
    return copy(
        PaymentState.CAPTURING,
        PaymentStage.CAPTURE,
        null,
        captureRequestId,
        providerAuthorizationId,
        null,
        null,
        now);
  }

  public Payment captureSucceeded(String providerReference, Instant now) {
    requireTransition(
        PaymentState.CAPTURE_CONFIRMED, PaymentState.CAPTURING, PaymentState.CAPTURE_UNKNOWN);
    requireReference(providerReference);
    return copy(
        PaymentState.CAPTURE_CONFIRMED,
        null,
        null,
        captureRequestId,
        providerAuthorizationId,
        providerReference,
        null,
        now);
  }

  public Payment captureAccounted(Instant now) {
    requireTransition(PaymentState.CAPTURE_ACCOUNTED, PaymentState.CAPTURE_CONFIRMED);
    return copy(
        PaymentState.CAPTURE_ACCOUNTED,
        null,
        null,
        captureRequestId,
        providerAuthorizationId,
        providerCaptureId,
        null,
        now);
  }

  public Payment captureFinalized(Instant now) {
    requireTransition(PaymentState.CAPTURED, PaymentState.CAPTURE_ACCOUNTED);
    return copy(
        PaymentState.CAPTURED,
        null,
        null,
        captureRequestId,
        providerAuthorizationId,
        providerCaptureId,
        null,
        now);
  }

  public Payment captureDeclined(String code, Instant now) {
    requireTransition(
        PaymentState.CAPTURE_DECLINED, PaymentState.CAPTURING, PaymentState.CAPTURE_UNKNOWN);
    requireCode(code);
    return copy(
        PaymentState.CAPTURE_DECLINED,
        null,
        null,
        captureRequestId,
        providerAuthorizationId,
        null,
        code,
        now);
  }

  public Payment captureUnknown(String code, Instant now) {
    requireTransition(PaymentState.CAPTURE_UNKNOWN, PaymentState.CAPTURING);
    requireCode(code);
    return copy(
        PaymentState.CAPTURE_UNKNOWN,
        PaymentStage.CAPTURE,
        null,
        captureRequestId,
        providerAuthorizationId,
        null,
        code,
        now);
  }

  public Payment captureRetryPending(String code, Instant now) {
    requireTransition(PaymentState.CAPTURE_RETRY_PENDING, PaymentState.CAPTURING);
    requireCode(code);
    return copy(
        PaymentState.CAPTURE_RETRY_PENDING,
        PaymentStage.CAPTURE,
        null,
        captureRequestId,
        providerAuthorizationId,
        null,
        code,
        now);
  }

  public Payment failed(String code, Instant now) {
    requireTransition(
        PaymentState.FAILED,
        PaymentState.AUTHORIZING,
        PaymentState.AUTHORIZATION_UNKNOWN,
        PaymentState.CAPTURING,
        PaymentState.CAPTURE_UNKNOWN);
    requireCode(code);
    return copy(
        PaymentState.FAILED,
        null,
        null,
        captureRequestId,
        providerAuthorizationId,
        null,
        code,
        now);
  }

  public UUID requestId(PaymentStage stage) {
    return switch (stage) {
      case AUTHORIZATION -> authorizationRequestId;
      case CAPTURE -> Objects.requireNonNull(captureRequestId, "capture request ID is not set");
    };
  }

  public int attemptCount(PaymentStage stage) {
    return switch (stage) {
      case AUTHORIZATION -> authorizationAttemptCount;
      case CAPTURE -> captureAttemptCount;
    };
  }

  private Payment copy(
      PaymentState target,
      PaymentStage newResumeStage,
      String newPaymentMethodReference,
      UUID newCaptureRequestId,
      String newProviderAuthorizationId,
      String newProviderCaptureId,
      String newFailureCode,
      Instant now) {
    return new Payment(
        paymentId,
        orderId,
        amount,
        target,
        newResumeStage,
        newPaymentMethodReference,
        authorizationRequestId,
        newCaptureRequestId,
        newProviderAuthorizationId,
        newProviderCaptureId,
        newFailureCode,
        authorizationAttemptCount,
        captureAttemptCount,
        version,
        createdAt,
        now);
  }

  private void requireTransition(PaymentState target, PaymentState... permitted) {
    if (!Set.of(permitted).contains(state)) {
      throw new IllegalPaymentTransitionException(state, target);
    }
  }

  private void requireReference(String reference) {
    if (reference == null || reference.isBlank() || reference.length() > 100) {
      throw new IllegalArgumentException("provider reference is invalid");
    }
  }

  private void requireCode(String code) {
    if (code == null || code.isBlank() || code.length() > 64) {
      throw new IllegalArgumentException("failure code is invalid");
    }
  }
}
