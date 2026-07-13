package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import java.util.UUID;

public interface PaymentProvider {

  ProviderResult authorize(AuthorizationRequest request);

  ProviderResult capture(CaptureRequest request);

  ProviderLookupResult lookup(PaymentStage stage, UUID providerRequestId, String correlationId);

  record AuthorizationRequest(
      UUID providerRequestId,
      UUID paymentId,
      UUID orderId,
      PaymentMoney amount,
      String paymentMethodReference,
      String correlationId) {}

  record CaptureRequest(
      UUID providerRequestId,
      UUID paymentId,
      UUID orderId,
      PaymentMoney amount,
      String providerAuthorizationId,
      String correlationId) {}
}
