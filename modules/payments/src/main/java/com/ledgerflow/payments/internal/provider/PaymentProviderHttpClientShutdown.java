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
        LOGGER.error("Payment provider HTTP client did not terminate within {}", timeout);
        httpClient.shutdownNow();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOGGER.error("Payment provider HTTP client shutdown was interrupted", exception);
      httpClient.shutdownNow();
    }
  }
}
