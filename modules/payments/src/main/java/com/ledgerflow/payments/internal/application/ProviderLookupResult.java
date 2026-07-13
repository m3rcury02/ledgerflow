package com.ledgerflow.payments.internal.application;

public sealed interface ProviderLookupResult {

  record FoundSuccess(String providerReference) implements ProviderLookupResult {}

  record FoundDecline(String failureCode) implements ProviderLookupResult {}

  record NotFound() implements ProviderLookupResult {}

  record TemporarilyUnavailable(String failureCode) implements ProviderLookupResult {}

  record InvalidResponse(String failureCode) implements ProviderLookupResult {}
}
