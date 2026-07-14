package com.ledgerflow.payments.internal.provider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentProviderHttpClientShutdownTest {

  @Test
  void forcesBoundedShutdownWhenOrderlyTerminationDoesNotFinish() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    Duration timeout = Duration.ofMillis(100);
    when(httpClient.awaitTermination(timeout)).thenReturn(false);

    new PaymentProviderHttpClientShutdown(httpClient, timeout).close();

    verify(httpClient).shutdown();
    verify(httpClient).awaitTermination(timeout);
    verify(httpClient).shutdownNow();
  }
}
