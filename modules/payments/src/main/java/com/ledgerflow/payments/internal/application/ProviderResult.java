package com.ledgerflow.payments.internal.application;

public sealed interface ProviderResult {

  record Success(String providerReference) implements ProviderResult {}

  record Declined(String failureCode) implements ProviderResult {}

  record TemporaryFailure(String failureCode) implements ProviderResult {}

  record Unknown(String failureCode) implements ProviderResult {}

  record InvalidResponse(String failureCode) implements ProviderResult {}
}
