package com.ledgerflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(TelemetryFailureIsolationIntegrationTest.FailingExporterConfiguration.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class TelemetryFailureIsolationIntegrationTest extends PaymentIntegrationTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private FailingSpanExporter failingExporter;
  @Autowired private SdkTracerProvider tracerProvider;

  @Test
  void exporterFailureDoesNotChangeTheBusinessTransaction() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(
                    jwt()
                        .jwt(token -> token.subject("telemetry-outage-customer"))
                        .authorities(
                            new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"),
                            new SimpleGrantedAuthority("ROLE_customer")))
                .header("Idempotency-Key", "telemetry-outage-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientReference": "telemetry-outage",
                      "amount": {"amountMinor": 259900, "currency": "INR"},
                      "paymentMethodReference": "pm_mock_success"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.payment.status").value("CAPTURED"));

    tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(failingExporter.exportCalls()).isPositive();
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM ledger_transactions").query(Long.class).single())
        .isOne();
    assertThat(jdbcClient.sql("SELECT count(*) FROM outbox_events").query(Long.class).single())
        .isOne();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailingExporterConfiguration {

    @Bean
    FailingSpanExporter failingSpanExporter() {
      return new FailingSpanExporter();
    }
  }

  static final class FailingSpanExporter implements SpanExporter {

    private final AtomicInteger exportCalls = new AtomicInteger();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      exportCalls.incrementAndGet();
      return CompletableResultCode.ofFailure();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    int exportCalls() {
      return exportCalls.get();
    }
  }
}
