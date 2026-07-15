package com.ledgerflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.ObservabilityTestConfiguration;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(ObservabilityTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class SensitiveTelemetryIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String SECRET_MARKER = "seeded-secret-marker-7a";
  private static final String PERSONAL_MARKER = "seeded-personal-marker-7a";

  @Autowired private MockMvc mockMvc;
  @Autowired private InMemorySpanExporter exporter;
  @Autowired private InMemoryLogRecordExporter logExporter;
  @Autowired private SdkTracerProvider tracerProvider;
  @Autowired private SdkLoggerProvider loggerProvider;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void resetSpans() {
    exporter.reset();
    logExporter.reset();
  }

  @Test
  void excludesSeededSecretsAndPersonalDataFromEveryTelemetrySignal(CapturedOutput output)
      throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", "Bearer " + SECRET_MARKER)
                    .header("Cookie", "session=" + SECRET_MARKER)
                    .header("Idempotency-Key", SECRET_MARKER)
                    .header("traceparent", "malformed-" + SECRET_MARKER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientReference": "%s",
                          "amount": {"amountMinor": 100, "currency": "INR"},
                          "paymentCredential": "%s"
                        }
                        """
                            .formatted(PERSONAL_MARKER, SECRET_MARKER)))
            .andExpect(status().isUnauthorized())
            .andExpect(header().exists("X-Correlation-Id"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    loggerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(response).doesNotContain(SECRET_MARKER, PERSONAL_MARKER);
    assertThat(output.getAll()).doesNotContain(SECRET_MARKER, PERSONAL_MARKER);
    assertThat(exporter.getFinishedSpanItems().toString())
        .doesNotContain(SECRET_MARKER, PERSONAL_MARKER);
    assertThat(logExporter.getFinishedLogRecordItems().toString())
        .doesNotContain(SECRET_MARKER, PERSONAL_MARKER);
    assertThat(meterRegistry.getMeters().toString()).doesNotContain(SECRET_MARKER, PERSONAL_MARKER);
    assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isZero();
  }
}
