package com.ledgerflow.payments.internal.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PaymentProviderHttpClientShutdown implements AutoCloseable {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PaymentProviderHttpClientShutdown.class);

  private final HttpClient httpClient;
  private final Duration timeout;

  PaymentProviderHttpClientShutdown(HttpClient httpClient, Duration timeout) {
    this.httpClient = httpClient;
    this.timeout = timeout;
  }

  @Override
  public void close() {
    httpClient.shutdown();
    try {
      if (!httpClient.awaitTermination(timeout)) {
        LOGGER
            .atError()
            .addKeyValue("event_code", "PAYMENT_PROVIDER_CLIENT_DRAIN_FAILED")
            .addKeyValue("action", "payment.provider.shutdown")
            .addKeyValue("error_code", "DRAIN_TIMEOUT")
            .log("Payment provider HTTP client did not terminate within its deadline");
        httpClient.shutdownNow();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOGGER
          .atError()
          .addKeyValue("event_code", "PAYMENT_PROVIDER_CLIENT_DRAIN_INTERRUPTED")
          .addKeyValue("action", "payment.provider.shutdown")
          .addKeyValue("error_code", "DRAIN_INTERRUPTED")
          .log("Payment provider HTTP client shutdown was interrupted");
      httpClient.shutdownNow();
    }
  }
}
