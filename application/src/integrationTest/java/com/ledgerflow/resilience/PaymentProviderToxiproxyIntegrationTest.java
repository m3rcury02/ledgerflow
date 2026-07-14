package com.ledgerflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.payments.internal.provider.HttpPaymentProviderAdapter;
import com.ledgerflow.testing.payment.MockPaymentProviderServer;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

class PaymentProviderToxiproxyIntegrationTest {

  private static final int PROXY_PORT = 8666;
  private static MockPaymentProviderServer providerServer;
  private static ToxiproxyContainer toxiproxy;
  private static Proxy proxy;
  private static HttpClient httpClient;

  @BeforeAll
  static void startDependencies() throws Exception {
    providerServer = new MockPaymentProviderServer();
    int providerPort = URI.create(providerServer.baseUrl()).getPort();
    Testcontainers.exposeHostPorts(providerPort);
    toxiproxy =
        new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withAccessToHost(true);
    toxiproxy.start();
    ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    proxy =
        client.createProxy(
            "payment-provider",
            "0.0.0.0:" + PROXY_PORT,
            "host.testcontainers.internal:" + providerPort);
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(150)).build();
  }

  @AfterAll
  static void stopDependencies() {
    if (httpClient != null) {
      httpClient.close();
    }
    if (toxiproxy != null) {
      toxiproxy.stop();
    }
    if (providerServer != null) {
      providerServer.stop();
    }
  }

  @Test
  void latencyWithinTheReadDeadlineSucceeds() throws Exception {
    Toxic latency = proxy.toxics().latency("provider-latency", ToxicDirection.DOWNSTREAM, 100L);
    try {
      ProviderResult result =
          provider(Duration.ofMillis(500), Duration.ofMillis(700)).authorize(request());
      assertThat(result).isInstanceOf(ProviderResult.Success.class);
    } finally {
      latency.remove();
    }
  }

  @Test
  void aStalledResponseProducesAnUnknownTimeoutOutcome() throws Exception {
    Toxic timeout = proxy.toxics().timeout("provider-timeout", ToxicDirection.DOWNSTREAM, 0L);
    try {
      ProviderResult result =
          provider(Duration.ofMillis(150), Duration.ofMillis(250)).authorize(request());
      assertThat(result).isEqualTo(new ProviderResult.Unknown("PROVIDER_TIMEOUT"));
    } finally {
      timeout.remove();
    }
  }

  @Test
  void aConnectionResetAfterDispatchDoesNotGetClassifiedAsAConfirmedDecline() throws Exception {
    Toxic reset = proxy.toxics().resetPeer("provider-reset", ToxicDirection.DOWNSTREAM, 0L);
    try {
      ProviderResult result =
          provider(Duration.ofMillis(300), Duration.ofMillis(500)).authorize(request());
      assertThat(result).isInstanceOf(ProviderResult.Unknown.class);
    } finally {
      reset.remove();
    }
  }

  private HttpPaymentProviderAdapter provider(Duration readTimeout, Duration overallTimeout) {
    return new HttpPaymentProviderAdapter(
        httpClient,
        new ObjectMapper(),
        URI.create("http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT)),
        readTimeout,
        overallTimeout);
  }

  private PaymentProvider.AuthorizationRequest request() {
    return new PaymentProvider.AuthorizationRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PaymentMoney(2599, "INR"),
        "pm_mock_success",
        "toxiproxy-provider-test");
  }
}
