package com.ledgerflow.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "ledgerflow.operations.health-probe-cache-ttl=250ms"})
class ManagementPortIntegrationTest extends PostgreSqlIntegrationTest {

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Value("${local.server.port}")
  private int applicationPort;

  @Value("${local.management.port}")
  private int managementPort;

  @Test
  void isolatesStatusOnlyHealthAndPrometheusFromTheApplicationPort() throws Exception {
    assertThat(managementPort).isNotEqualTo(applicationPort);

    HttpResponse<String> applicationReadiness = get(applicationPort, "/actuator/health/readiness");
    assertThat(applicationReadiness.statusCode()).isNotEqualTo(200);

    HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness");
    HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
    assertStatusOnly(liveness);
    assertStatusOnly(readiness);

    HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus");
    assertThat(prometheus.statusCode()).isEqualTo(200);
    assertThat(prometheus.body())
        .contains(
            "ledgerflow_readiness_status",
            "ledgerflow_graceful_drain_active",
            "jvm_threads_live_threads",
            "hikaricp_connections_active")
        .doesNotContain("owner_subject", "idempotency_key", "payment_method_reference");
    assertThat(get(managementPort, "/actuator/health").statusCode()).isNotEqualTo(200);
    assertThat(get(managementPort, "/actuator/info").statusCode()).isNotEqualTo(200);
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertStatusOnly(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    assertThat(response.body())
        .doesNotContain(
            "components", "details", "database", "kafka", "cluster", "exception", "host");
  }
}
