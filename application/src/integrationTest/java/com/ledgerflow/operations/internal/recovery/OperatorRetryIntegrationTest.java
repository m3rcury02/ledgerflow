package com.ledgerflow.operations.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.operations.internal.OperationsProperties;
import com.ledgerflow.testing.ObservabilityTestConfiguration;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
@Import(ObservabilityTestConfiguration.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class OperatorRetryIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String REASON =
      "The infrastructure incident is resolved and this operation is safe to retry.";

  @Autowired private MockMvc mockMvc;
  @Autowired private OperatorRecoveryStore store;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OpenTelemetry openTelemetry;
  @Autowired private InMemorySpanExporter spanExporter;
  @Autowired private SdkTracerProvider tracerProvider;

  @Test
  void concurrentDuplicateRequestsCreateOneLogicalCommand() throws Exception {
    UUID eventId = insertFailedOutbox();
    String operationId = "outbox_" + eventId;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> first =
          executor.submit(() -> submit(operationId, "concurrent-retry-key-0001", REASON, null));
      Future<MvcResult> second =
          executor.submit(() -> submit(operationId, "concurrent-retry-key-0001", REASON, null));
      MvcResult firstResult = first.get();
      MvcResult secondResult = second.get();
      assertThat(firstResult.getResponse().getStatus()).isEqualTo(202);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(202);
      assertThat(commandId(firstResult)).isEqualTo(commandId(secondResult));
      assertThat(count("operator_retry_commands")).isOne();

      mockMvc
          .perform(
              post("/api/v1/operator/operations/{operationId}/retries", operationId)
                  .with(jwtFor("operator-one", "ledgerflow.operations.retry", "operator"))
                  .header("Idempotency-Key", "different-active-key-0001")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(retryBody("A second active command must be rejected safely.")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("retry_already_active"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredLeaseAllowsTakeoverAndRejectsStaleCompletion() throws Exception {
    UUID eventId = insertFailedOutbox();
    UUID retryId = commandId(submit("outbox_" + eventId, "lease-retry-key-0001", REASON, null));
    Instant claimTime = Instant.now().plusSeconds(1);
    RetryClaim first =
        store.claimBatch("worker-one", 1, claimTime, Duration.ofSeconds(5)).getFirst();
    assertThat(store.claimBatch("worker-two", 1, claimTime, Duration.ofSeconds(5))).isEmpty();

    Instant takeoverTime = claimTime.plusSeconds(6);
    RetryClaim takeover =
        store.claimBatch("worker-two", 1, takeoverTime, Duration.ofSeconds(5)).getFirst();
    assertThat(takeover.takeover()).isTrue();
    assertThat(takeover.command().id()).isEqualTo(retryId);
    assertThat(store.leaseIsCurrent(first.command(), takeoverTime)).isFalse();
    assertThat(store.markCompleted(first.command(), "OUTBOX_PUBLISHED", takeoverTime)).isFalse();
    store.recordStaleCompletion(first.command(), takeoverTime);
    assertThat(
            store.markCompleted(
                takeover.command(), "OUTBOX_PUBLISHED", takeoverTime.plusSeconds(1)))
        .isTrue();

    RetryCommand completed =
        store
            .findRetry(
                new OperationReference(com.ledgerflow.operations.api.OperationType.OUTBOX, eventId),
                retryId)
            .orElseThrow();
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(
            jdbcClient
                .sql("SELECT action FROM operator_retry_attempts ORDER BY occurred_at, id")
                .query(String.class)
                .list())
        .containsExactly("CLAIMED", "TAKEN_OVER", "STALE_REJECTED", "COMPLETED");
  }

  @Test
  void enforcesTransactionalCooldownAttemptCapsAndSeparateBreakGlassApproval() throws Exception {
    UUID eventId = insertFailedOutbox();
    String operationId = "outbox_" + eventId;

    failAcceptedCommand(operationId, "automatic-retry-key-0001", 1);
    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-one", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "automatic-retry-key-cooldown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(retryBody(REASON)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("retry_cooldown"));

    makeRetryAvailable(eventId);
    failAcceptedCommand(operationId, "automatic-retry-key-0002", 2);
    makeRetryAvailable(eventId);
    failAcceptedCommand(operationId, "automatic-retry-key-0003", 3);
    makeRetryAvailable(eventId);

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-one", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "automatic-retry-key-0004")
                .contentType(MediaType.APPLICATION_JSON)
                .content(retryBody(REASON)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("retry_limit_reached"));

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-one", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "invalid-break-glass-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(retryBody(REASON, UUID.randomUUID())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("break_glass_approval_used"));

    MvcResult approval =
        mockMvc
            .perform(
                post("/api/v1/operator/operations/{operationId}/break-glass-approvals", operationId)
                    .with(jwtFor("admin-approver", "ledgerflow.operations.break-glass", "admin"))
                    .header("Idempotency-Key", "break-glass-approval-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "approvalReference": "INCIDENT-2026-7001",
                          "reason": "Incident commander approved one exceptional recovery attempt."
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn();
    String approvalId = JsonPath.read(approval.getResponse().getContentAsString(), "$.approvalId");

    MvcResult breakGlassRetry =
        submit(
            operationId,
            "break-glass-retry-key-0001",
            "Execute the separately approved exceptional recovery attempt.",
            UUID.fromString(approvalId));
    assertThat(breakGlassRetry.getResponse().getStatus()).isEqualTo(202);
    assertThat(
            JsonPath.<String>read(
                breakGlassRetry.getResponse().getContentAsString(), "$.attemptKind"))
        .isEqualTo("BREAK_GLASS");
    assertThat(
            jdbcClient
                .sql("SELECT action FROM operator_audit_records ORDER BY occurred_at, id")
                .query(String.class)
                .list())
        .containsSubsequence("BREAK_GLASS_APPROVED", "BREAK_GLASS_USED", "RETRY_ACCEPTED");
  }

  @Test
  void privilegedEvidenceIsImmutableAndRetryStatusIsSanitized() throws Exception {
    UUID eventId = insertFailedOutbox();
    String operationId = "outbox_" + eventId;
    UUID retryId = commandId(submit(operationId, "immutable-audit-key-0001", REASON, null));

    mockMvc
        .perform(
            get("/api/v1/operator/operations/{operationId}/retries/{retryId}", operationId, retryId)
                .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retryId").value(retryId.toString()))
        .andExpect(jsonPath("$.reason").doesNotExist());

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("UPDATE operator_audit_records SET reason = 'Tampered audit evidence.'")
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM operator_audit_records").update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM operator_retry_commands").update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void workerSpanLinksToTheAuthenticatedRetryRequestAndOriginalFailure() throws Exception {
    spanExporter.reset();
    UUID eventId = insertFailedOutbox();
    String operationId = "outbox_" + eventId;
    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("trace-operator", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "trace-recovery-key-0001")
                .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(retryBody(REASON)))
        .andExpect(status().isAccepted());

    OperationRecoveryHandler handler =
        new OperationRecoveryHandler() {
          @Override
          public OperationType operationType() {
            return OperationType.OUTBOX;
          }

          @Override
          public OperationRecoveryResult recover(
              com.ledgerflow.operations.api.OperationRecoveryContext context) {
            context.leaseGuard().requireCurrent();
            return OperationRecoveryResult.completed("OUTBOX_RECOVERY_TESTED");
          }
        };
    WorkTracker tracker =
        new WorkTracker() {
          @Override
          public WorkToken begin(String operation) {
            return () -> assertThat(operation).isNotBlank();
          }

          @Override
          public boolean isAcceptingWork() {
            return true;
          }
        };
    OperationsProperties properties =
        new OperationsProperties(
            Duration.ofSeconds(20),
            Duration.ofSeconds(3),
            Duration.ofSeconds(2),
            true,
            10,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(2),
            Duration.ofMinutes(5),
            3,
            2,
            true);
    OperatorRetryWorker worker =
        new OperatorRetryWorker(
            store,
            List.of(handler),
            properties,
            new OperatorRecoveryMetrics(meterRegistry),
            tracker,
            java.time.Clock.systemUTC(),
            openTelemetry);
    worker.processPendingCommands();
    tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

    var workerSpan =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> span.getName().equals("operator.recovery.execute"))
            .findFirst()
            .orElseThrow();
    assertThat(
            workerSpan.getLinks().stream().map(link -> link.getSpanContext().getTraceId()).toList())
        .contains("11111111111111111111111111111111", "33333333333333333333333333333333");
    assertThat(workerSpan.getAttributes().asMap().toString())
        .doesNotContain(REASON, eventId.toString());
  }

  private void failAcceptedCommand(String operationId, String key, int attemptNumber)
      throws Exception {
    UUID commandId = commandId(submit(operationId, key, REASON, null));
    Instant now = Instant.now().plusSeconds(attemptNumber);
    List<RetryClaim> claims =
        store.claimBatch("failure-worker-" + attemptNumber, 1, now, Duration.ofSeconds(30));
    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().command().id()).isEqualTo(commandId);
    assertThat(
            store.markFailed(
                claims.getFirst().command(),
                "RECOVERY_HANDLER_FAILED",
                now.plus(Duration.ofMinutes(5)),
                now.plusMillis(1)))
        .isTrue();
  }

  private void makeRetryAvailable(UUID eventId) {
    jdbcClient
        .sql(
            """
            UPDATE operator_recovery_state
            SET retry_available_at = statement_timestamp() - interval '1 second',
                updated_at = statement_timestamp(), version = version + 1
            WHERE operation_type = 'OUTBOX' AND source_id = :sourceId
            """)
        .param("sourceId", eventId)
        .update();
  }

  private MvcResult submit(String operationId, String key, String reason, UUID approvalId)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-one", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(retryBody(reason, approvalId)))
        .andReturn();
  }

  private String retryBody(String reason) {
    return retryBody(reason, null);
  }

  private String retryBody(String reason, UUID approvalId) {
    String approval = approvalId == null ? "" : ",\"approvalId\":\"" + approvalId + "\"";
    return "{\"reason\":\"" + reason + "\"" + approval + '}';
  }

  private UUID commandId(MvcResult result) {
    return UUID.fromString(
        JsonPath.read(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
            "$.retryId"));
  }

  private RequestPostProcessor jwtFor(String subject, String scope, String role) {
    return jwt()
        .jwt(
            token ->
                token
                    .issuer("https://issuer.integration-test.invalid")
                    .subject(subject)
                    .claim("azp", "ledgerflow-operator-cli"))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_" + scope),
            new SimpleGrantedAuthority("ROLE_" + role));
  }

  private UUID insertFailedOutbox() throws Exception {
    UUID eventId = UUID.randomUUID();
    String payload = "{\"event\":\"operator-recovery-test\"}";
    Instant now = Instant.now();
    jdbcClient
        .sql(
            """
            INSERT INTO outbox_events (
                event_id, deduplication_key, aggregate_type, aggregate_id,
                event_type, schema_version, topic, event_key, payload, payload_hash,
                correlation_id, causation_id, occurred_at, status,
                traceparent,
                cycle_attempt_count, total_attempt_count, available_at,
                last_failure_code, last_failed_at, created_at
            ) VALUES (
                :eventId, :deduplicationKey, 'PAYMENT', :aggregateId,
                'payment.captured', 1, 'ledgerflow.payment-captured.v1', :eventKey,
                CAST(:payload AS jsonb), :payloadHash, 'operator-test', :causationId,
                :occurredAt, 'FAILED',
                '00-33333333333333333333333333333333-4444444444444444-01',
                3, 4, :occurredAt,
                'KAFKA_SEND_FAILED', :occurredAt, :occurredAt
            )
            """)
        .param("eventId", eventId)
        .param("deduplicationKey", "operator-test:" + eventId)
        .param("aggregateId", UUID.randomUUID())
        .param("eventKey", eventId.toString())
        .param("payload", payload)
        .param(
            "payloadHash",
            MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)))
        .param("causationId", UUID.randomUUID())
        .param("occurredAt", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        .update();
    return eventId;
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
