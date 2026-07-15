package com.ledgerflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.messaging.internal.application.OutboxPublisher;
import com.ledgerflow.testing.KafkaIntegrationTest;
import com.ledgerflow.testing.ObservabilityTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(ObservabilityTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class TracePropagationIntegrationTest extends KafkaIntegrationTest {

  private static final String TRACE_ID = "11111111111111111111111111111111";

  @Autowired private MockMvc mockMvc;
  @Autowired private OutboxPublisher outboxPublisher;
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
  void preservesOneTraceAcrossHttpProviderDatabaseOutboxKafkaAndNotification(CapturedOutput output)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(
                    jwt()
                        .jwt(token -> token.subject("trace-customer"))
                        .authorities(
                            new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"),
                            new SimpleGrantedAuthority("ROLE_customer")))
                .header("Idempotency-Key", "trace-order-key-0001")
                .header("X-Correlation-Id", "trace-order-correlation")
                .header("traceparent", "00-" + TRACE_ID + "-2222222222222222-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientReference": "trace-order",
                      "amount": {"amountMinor": 259900, "currency": "INR"},
                      "paymentMethodReference": "pm_mock_success"
                    }
                    """))
        .andExpect(status().isCreated());

    assertThat(jdbcClient.sql("SELECT traceparent FROM outbox_events").query(String.class).single())
        .startsWith("00-" + TRACE_ID + "-");
    assertThat(PROVIDER.traceparents()).allMatch(value -> value.startsWith("00-" + TRACE_ID + "-"));

    outboxPublisher.publishBatch();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbcClient
                            .sql("SELECT count(*) FROM notifications")
                            .query(Long.class)
                            .single())
                    .isOne());

    tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    loggerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(exporter.getFinishedSpanItems())
                    .anyMatch(span -> "notification.process".equals(span.getName())));

    List<SpanData> trace =
        exporter.getFinishedSpanItems().stream()
            .filter(span -> TRACE_ID.equals(span.getTraceId()))
            .toList();
    Set<String> names = trace.stream().map(SpanData::getName).collect(Collectors.toSet());
    assertThat(names)
        .contains(
            "order.workflow",
            "db.order.initialize",
            "payment.provider.authorize",
            "payment.provider.capture",
            "ledger.capture-accounting",
            "outbox.append",
            "db.order.finalize",
            "outbox.publish",
            "notification.process");
    assertThat(trace).anyMatch(span -> span.getKind() == SpanKind.SERVER);
    assertThat(trace).anyMatch(span -> span.getKind() == SpanKind.PRODUCER);
    assertThat(trace).anyMatch(span -> span.getKind() == SpanKind.CONSUMER);

    SpanData order = named(trace, "order.workflow");
    assertThat(hasAncestorOfKind(order, trace, SpanKind.SERVER)).isTrue();
    SpanData append = named(trace, "outbox.append");
    SpanData publish = named(trace, "outbox.publish");
    assertThat(publish.getParentSpanId()).isEqualTo(append.getSpanId());
    assertThat(meterRegistry.find("kafka.consumer.fetch.manager.records.lag.max").gauges())
        .isNotEmpty();
    assertThat(output.getAll())
        .contains(TRACE_ID, "trace-order-correlation")
        .doesNotContain("trace-customer", "trace-order-key-0001");

    List<LogRecordData> traceLogs =
        logExporter.getFinishedLogRecordItems().stream()
            .filter(log -> TRACE_ID.equals(log.getSpanContext().getTraceId()))
            .toList();
    assertThat(traceLogs).isNotEmpty();
    assertThat(traceLogs.toString())
        .contains("trace-order-correlation", "event_code")
        .doesNotContain(
            "trace-customer",
            "trace-order-key-0001",
            "Executing prepared SQL",
            "OrderResponse",
            "clientReference");
  }

  private SpanData named(List<SpanData> trace, String name) {
    return trace.stream().filter(span -> name.equals(span.getName())).findFirst().orElseThrow();
  }

  private boolean hasAncestorOfKind(SpanData span, List<SpanData> trace, SpanKind kind) {
    String parentSpanId = span.getParentSpanId();
    for (int depth = 0; depth < trace.size(); depth++) {
      String currentParentSpanId = parentSpanId;
      SpanData parent =
          trace.stream()
              .filter(candidate -> candidate.getSpanId().equals(currentParentSpanId))
              .findFirst()
              .orElse(null);
      if (parent == null) {
        return false;
      }
      if (parent.getKind() == kind) {
        return true;
      }
      parentSpanId = parent.getParentSpanId();
    }
    return false;
  }
}
