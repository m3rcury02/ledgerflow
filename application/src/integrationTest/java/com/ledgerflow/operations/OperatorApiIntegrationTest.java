package com.ledgerflow.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class OperatorApiIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String RETRY_BODY =
      """
      {"reason":"The broker incident is resolved and the durable event can be retried."}
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private List<OperationRecoveryHandler> recoveryHandlers;

  @Test
  void enforcesTheOperatorBoundaryAndSeparatePermissions() throws Exception {
    UUID eventId = insertFailedOutbox("security-marker");
    String operationId = "outbox_" + eventId;

    mockMvc.perform(get("/api/v1/operator/operations")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/v1/operator/operations")
                .with(jwtFor("customer", "ledgerflow.operations.read", "customer")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/operator/operations")
                .with(jwtFor("operator", "ledgerflow.operations.retry", "operator")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator", "ledgerflow.operations.read", "operator"))
                .header("Idempotency-Key", "operator-security-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RETRY_BODY))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/break-glass-approvals", operationId)
                .with(jwtFor("operator", "ledgerflow.operations.break-glass", "operator"))
                .header("Idempotency-Key", "operator-security-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "approvalReference": "INCIDENT-2026-0002",
                      "reason": "Approved after incident review and evidence inspection."
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void paginatesAndNeverProjectsStoredPayloadOrCustomerData() throws Exception {
    String secretMarker = "seeded-sensitive-customer-marker";
    UUID first = insertFailedOutbox(secretMarker);
    insertFailedOutbox("second-marker");

    MvcResult firstPage =
        mockMvc
            .perform(
                get("/api/v1/operator/operations")
                    .param("limit", "1")
                    .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn();
    String response = firstPage.getResponse().getContentAsString();
    assertThat(response).doesNotContain(secretMarker, "customerSubject", "validatedPayload");
    String cursor = JsonPath.read(response, "$.nextCursor");
    mockMvc
        .perform(
            get("/api/v1/operator/operations")
                .param("limit", "1")
                .param("cursor", cursor)
                .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/operator/operations/{operationId}", "outbox_" + first)
                .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operation.operationType").value("OUTBOX"))
        .andExpect(jsonPath("$.attempts[0].source").value("OUTBOX"));

    mockMvc
        .perform(
            get("/api/v1/operator/operations/{operationId}", "payment_" + first)
                .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
        .andExpect(status().isNotFound());

    jdbcClient
        .sql(
            """
            UPDATE outbox_events
            SET status = 'PUBLISHED', last_failure_code = NULL, last_failed_at = NULL,
                published_at = statement_timestamp()
            WHERE event_id = :eventId
            """)
        .param("eventId", first)
        .update();
    mockMvc
        .perform(
            get("/api/v1/operator/operations/{operationId}", "outbox_" + first)
                .with(jwtFor("reader", "ledgerflow.operations.read", "operator")))
        .andExpect(status().isNotFound());
  }

  @Test
  void retryCommandIsIdempotentAuditedAndResetsOnlyTheExistingOutboxRecord() throws Exception {
    UUID eventId = insertFailedOutbox("immutable-payload-marker");
    String operationId = "outbox_" + eventId;

    MvcResult accepted =
        mockMvc
            .perform(
                post("/api/v1/operator/operations/{operationId}/retries", operationId)
                    .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                    .header("Idempotency-Key", "operator-retry-0001")
                    .header("X-Correlation-Id", "operator-recovery-test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(RETRY_BODY))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
    String retryId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.retryId");

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "operator-retry-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RETRY_BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.retryId").value(retryId));

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "operator-retry-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"reason":"A different payload must conflict with the original command."}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

    assertThat(count("operator_retry_commands")).isOne();
    assertThat(count("operator_audit_records")).isOne();
    assertThat(
            jdbcClient
                .sql("SELECT actor_subject FROM operator_audit_records")
                .query(String.class)
                .single())
        .isEqualTo("operator-subject");

    OperationRecoveryResult recovery =
        handler(OperationType.OUTBOX)
            .recover(
                new OperationRecoveryContext(
                    UUID.fromString(retryId),
                    eventId,
                    "operator-recovery-test",
                    true,
                    false,
                    java.time.Duration.ofSeconds(2),
                    () -> assertThat(true).isTrue()));
    assertThat(recovery.status()).isEqualTo(OperationRecoveryResult.Status.WAITING);
    assertThat(
            jdbcClient
                .sql("SELECT status FROM outbox_events WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single())
        .isEqualTo("PENDING");
    assertThat(count("outbox_events")).isOne();
    assertThat(
            jdbcClient
                .sql("SELECT total_attempt_count FROM outbox_events WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single())
        .isEqualTo(4L);

    jdbcClient
        .sql(
            """
            UPDATE outbox_events
            SET status = 'FAILED', cycle_attempt_count = 10, total_attempt_count = 14,
                last_failure_code = 'KAFKA_SEND_FAILED',
                last_failed_at = statement_timestamp()
            WHERE event_id = :id
            """)
        .param("id", eventId)
        .update();
    OperationRecoveryResult exhaustedCycle =
        handler(OperationType.OUTBOX)
            .recover(
                new OperationRecoveryContext(
                    UUID.fromString(retryId),
                    eventId,
                    "operator-recovery-test",
                    false,
                    false,
                    java.time.Duration.ofSeconds(2),
                    () -> assertThat(true).isTrue()));
    assertThat(exhaustedCycle.status()).isEqualTo(OperationRecoveryResult.Status.FAILED);
    assertThat(
            jdbcClient
                .sql("SELECT status FROM outbox_events WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single())
        .isEqualTo("FAILED");
  }

  @Test
  void identicalRetryStillReturnsTheOriginalCommandAfterTheSourceIsResolved() throws Exception {
    UUID eventId = insertFailedOutbox("resolved-idempotency-marker");
    String operationId = "outbox_" + eventId;

    MvcResult accepted =
        mockMvc
            .perform(
                post("/api/v1/operator/operations/{operationId}/retries", operationId)
                    .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                    .header("Idempotency-Key", "operator-resolved-replay-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(RETRY_BODY))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andReturn();
    String retryId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.retryId");

    jdbcClient
        .sql(
            """
            UPDATE outbox_events
            SET status = 'PUBLISHED', last_failure_code = NULL, last_failed_at = NULL,
                published_at = statement_timestamp()
            WHERE event_id = :eventId
            """)
        .param("eventId", eventId)
        .update();

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "operator-resolved-replay-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RETRY_BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.retryId").value(retryId));

    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", operationId)
                .with(jwtFor("operator-subject", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "operator-resolved-replay-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"reason":"Changed content must conflict after the source is resolved."}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
  }

  @Test
  void rejectsMalformedTerminalDeadLetterAsNonRecoverable() throws Exception {
    UUID deadLetterId = insertNonReplayableDeadLetter();
    mockMvc
        .perform(
            post("/api/v1/operator/operations/{operationId}/retries", "dead-letter_" + deadLetterId)
                .with(jwtFor("operator", "ledgerflow.operations.retry", "operator"))
                .header("Idempotency-Key", "malformed-dlt-retry-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RETRY_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("operation_not_retryable"));
    assertThat(count("operator_retry_commands")).isZero();
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
      String subject, String scope, String role) {
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

  private OperationRecoveryHandler handler(OperationType type) {
    return recoveryHandlers.stream()
        .filter(candidate -> candidate.operationType() == type)
        .findFirst()
        .orElseThrow();
  }

  private UUID insertFailedOutbox(String marker) throws Exception {
    UUID eventId = UUID.randomUUID();
    Instant failedAt = Instant.now();
    String payload = "{\"marker\":\"" + marker + "\"}";
    jdbcClient
        .sql(
            """
            INSERT INTO outbox_events (
                event_id, deduplication_key, aggregate_type, aggregate_id,
                event_type, schema_version, topic, event_key, payload, payload_hash,
                correlation_id, causation_id, occurred_at, status,
                cycle_attempt_count, total_attempt_count, available_at,
                last_failure_code, last_failed_at, created_at
            ) VALUES (
                :eventId, :deduplicationKey, 'PAYMENT', :aggregateId,
                'payment.captured', 1, 'ledgerflow.payment-captured.v1', :eventKey,
                CAST(:payload AS jsonb), :payloadHash, 'operator-test', :causationId,
                :occurredAt, 'FAILED', 3, 4, :occurredAt,
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
        .param("occurredAt", java.time.OffsetDateTime.ofInstant(failedAt, java.time.ZoneOffset.UTC))
        .update();
    return eventId;
  }

  private UUID insertNonReplayableDeadLetter() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest("malformed".getBytes(StandardCharsets.UTF_8));
    jdbcClient
        .sql(
            """
            INSERT INTO dead_letter_records (
                id, consumer_name, original_topic, original_partition, original_offset,
                payload_hash, payload_size, failure_code, failure_summary,
                attempt_count, replayable
            ) VALUES (
                :id, 'ledgerflow-notifications-v1', 'ledgerflow.payment-captured.v1', 0, 91,
                :hash, 9, 'DLT_EVENT_INVALID', 'Malformed terminal DLT evidence.', 4, false
            )
            """)
        .param("id", id)
        .param("hash", hash)
        .update();
    return id;
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
