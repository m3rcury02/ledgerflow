package com.ledgerflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "ledgerflow.messaging.metrics-refresh-initial-delay=10ms",
      "ledgerflow.messaging.metrics-refresh-interval=100ms"
    })
class ObservabilityIntegrationTest extends PaymentIntegrationTestSupport {

  private static final Set<String> ALLOWED_LABELS =
      Set.of("stage", "activity", "state", "executor", "outcome");

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void exposesBoundedWorkflowMetricsAndSafelyReplacesMalformedContext() throws Exception {
    String malformedCorrelation = "invalid correlation value";
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(
                    jwt()
                        .jwt(token -> token.subject("observability-customer"))
                        .authorities(
                            new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"),
                            new SimpleGrantedAuthority("ROLE_customer")))
                .header("Idempotency-Key", "observability-order-key-0001")
                .header("X-Correlation-Id", malformedCorrelation)
                .header("traceparent", "not-a-w3c-traceparent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientReference": "observability-order",
                      "amount": {"amountMinor": 259900, "currency": "INR"},
                      "paymentMethodReference": "pm_mock_success"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("X-Correlation-Id", org.hamcrest.Matchers.not(malformedCorrelation)))
        .andExpect(
            header()
                .string(
                    "X-Correlation-Id",
                    org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9._-]{1,64}")));

    assertCounter("ledgerflow.orders.workflow", "outcome", "created", 1.0);
    assertCounter("ledgerflow.orders.workflow", "outcome", "completed", 1.0);
    assertCounter("ledgerflow.payments.outcomes", "outcome", "success", 2.0);
    assertCounter("ledgerflow.payments.state.transitions", "state", "captured", 1.0);
    assertCounter("ledgerflow.payment.provider.attempts", "outcome", "success", 2.0);
    assertCounter("ledgerflow.ledger.postings", "outcome", "success", 1.0);
    assertCounter("ledgerflow.outbox.appends", "outcome", "created", 1.0);
    assertThat(meterRegistry.find("http.server.requests").timer()).isNotNull();

    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertThat(
                        meterRegistry
                            .find("ledgerflow.outbox.records")
                            .tag("state", "due")
                            .gauge()
                            .value())
                    .isEqualTo(1.0));

    for (Meter meter : meterRegistry.getMeters()) {
      if (!meter.getId().getName().startsWith("ledgerflow.")) {
        continue;
      }
      meter
          .getId()
          .getTags()
          .forEach(
              tag -> {
                assertThat(tag.getKey()).isIn(ALLOWED_LABELS);
                assertThat(tag.getValue())
                    .doesNotContain(
                        "observability-customer", "observability-order-key", malformedCorrelation);
                assertThat(tag.getValue()).hasSizeLessThanOrEqualTo(32);
              });
    }
  }

  private void assertCounter(String name, String tagName, String tagValue, double expectedMinimum) {
    double total =
        meterRegistry.find(name).tag(tagName, tagValue).counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count)
            .sum();
    assertThat(total).isGreaterThanOrEqualTo(expectedMinimum);
  }
}
