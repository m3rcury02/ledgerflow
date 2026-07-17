package com.ledgerflow.messaging.internal.persistence;

import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.messaging.internal.application.OutboxIntegrityException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxStore {

  private final JdbcClient jdbcClient;

  public JdbcOutboxStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID nextEventId() {
    return jdbcClient.sql("SELECT uuidv7()").query(UUID.class).single();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> findPaymentCapturedEventId(UUID paymentId) {
    return jdbcClient
        .sql("SELECT event_id FROM outbox_events WHERE deduplication_key = :deduplicationKey")
        .param("deduplicationKey", "payment-captured:" + paymentId)
        .query(UUID.class)
        .optional();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID insertOrVerify(
      PaymentCapturedEventV1 event,
      String topic,
      String eventKey,
      String canonicalPayload,
      byte[] payloadHash,
      String traceparent,
      String tracestate) {
    String deduplicationKey = "payment-captured:" + event.aggregateId();
    int inserted =
        jdbcClient
            .sql(
                """
                INSERT INTO outbox_events (
                    event_id, deduplication_key, aggregate_type, aggregate_id,
                    event_type, schema_version, topic, event_key, payload, payload_hash,
                    correlation_id, causation_id, occurred_at, traceparent, tracestate
                ) VALUES (
                    :eventId, :deduplicationKey, 'PAYMENT', :aggregateId,
                    :eventType, :schemaVersion, :topic, :eventKey,
                    CAST(:payload AS jsonb), :payloadHash, :correlationId, :causationId,
                    :occurredAt, :traceparent, :tracestate
                )
                ON CONFLICT (deduplication_key) DO NOTHING
                """)
            .param("eventId", event.eventId())
            .param("deduplicationKey", deduplicationKey)
            .param("aggregateId", event.aggregateId())
            .param("eventType", event.eventType())
            .param("schemaVersion", event.schemaVersion())
            .param("topic", topic)
            .param("eventKey", eventKey)
            .param("payload", canonicalPayload)
            .param("payloadHash", payloadHash)
            .param("correlationId", event.correlationId())
            .param("causationId", event.causationId())
            .param("occurredAt", databaseTimestamp(event.occurredAt()))
            .param("traceparent", traceparent, Types.VARCHAR)
            .param("tracestate", tracestate, Types.VARCHAR)
            .update();
    if (inserted == 1) {
      return event.eventId();
    }
    ExistingEvent existing =
        jdbcClient
            .sql(
                """
                SELECT event_id, topic, event_key, event_type, schema_version, aggregate_id,
                       correlation_id, causation_id, occurred_at, payload_hash
                FROM outbox_events
                WHERE deduplication_key = :deduplicationKey
                """)
            .param("deduplicationKey", deduplicationKey)
            .query(this::mapExisting)
            .single();
    if (!existing.matches(event, topic, eventKey, payloadHash)) {
      throw new OutboxIntegrityException(
          "Payment-captured outbox identity already exists with different event data");
    }
    return existing.eventId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<OutboxRecord> claimBatch(
      String leaseOwner, int batchSize, Instant now, Duration leaseDuration) {
    List<UUID> candidates =
        jdbcClient
            .sql(
                """
                SELECT event_id
                FROM outbox_events
                WHERE (status = 'PENDING' AND available_at <= :now)
                   OR (status = 'IN_FLIGHT' AND lease_until <= :now)
                ORDER BY available_at, created_at, event_id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
                """)
            .param("now", databaseTimestamp(now))
            .param("batchSize", batchSize)
            .query(UUID.class)
            .list();
    Instant leaseUntil = now.plus(leaseDuration);
    for (UUID eventId : candidates) {
      jdbcClient
          .sql(
              """
              UPDATE outbox_events
              SET status = 'IN_FLIGHT', lease_owner = :leaseOwner, lease_until = :leaseUntil,
                  cycle_attempt_count = cycle_attempt_count + 1,
                  total_attempt_count = total_attempt_count + 1,
                  last_failure_code = NULL, last_failed_at = NULL
              WHERE event_id = :eventId
              """)
          .param("leaseOwner", leaseOwner)
          .param("leaseUntil", databaseTimestamp(leaseUntil))
          .param("eventId", eventId)
          .update();
    }
    if (candidates.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            SELECT event_id, topic, event_key, event_type, schema_version, aggregate_id,
                   correlation_id, causation_id, occurred_at, payload::text AS payload,
                   payload_hash, traceparent, tracestate, lease_owner,
                   cycle_attempt_count, total_attempt_count
            FROM outbox_events
            WHERE lease_owner = :leaseOwner AND status = 'IN_FLIGHT'
            ORDER BY available_at, created_at, event_id
            """)
        .param("leaseOwner", leaseOwner)
        .query(this::mapRecord)
        .list();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPublished(UUID eventId, String leaseOwner, Instant now) {
    return jdbcClient
            .sql(
                """
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = :now,
                    lease_owner = NULL, lease_until = NULL,
                    last_failure_code = NULL, last_failed_at = NULL
                WHERE event_id = :eventId AND status = 'IN_FLIGHT'
                  AND lease_owner = :leaseOwner AND lease_until >= :now
                """)
            .param("now", databaseTimestamp(now))
            .param("eventId", eventId)
            .param("leaseOwner", leaseOwner)
            .update()
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markFailed(
      UUID eventId,
      String leaseOwner,
      Instant now,
      boolean exhausted,
      Duration nextDelay,
      String failureCode) {
    String status = exhausted ? "FAILED" : "PENDING";
    Instant availableAt = exhausted ? now : now.plus(nextDelay);
    return jdbcClient
            .sql(
                """
                UPDATE outbox_events
                SET status = :status, available_at = :availableAt,
                    lease_owner = NULL, lease_until = NULL,
                    last_failure_code = :failureCode, last_failed_at = :now
                WHERE event_id = :eventId AND status = 'IN_FLIGHT'
                  AND lease_owner = :leaseOwner AND lease_until >= :now
                """)
            .param("status", status)
            .param("availableAt", databaseTimestamp(availableAt))
            .param("failureCode", failureCode)
            .param("eventId", eventId)
            .param("leaseOwner", leaseOwner)
            .update()
        == 1;
  }

  public Optional<OutboxRecord> find(UUID eventId) {
    return jdbcClient
        .sql(
            """
            SELECT event_id, topic, event_key, event_type, schema_version, aggregate_id,
                   correlation_id, causation_id, occurred_at, payload::text AS payload,
                   payload_hash, traceparent, tracestate, lease_owner,
                   cycle_attempt_count, total_attempt_count
            FROM outbox_events WHERE event_id = :eventId
            """)
        .param("eventId", eventId)
        .query(this::mapRecord)
        .optional();
  }

  public BacklogSnapshot backlog(Instant now) {
    return jdbcClient
        .sql(
            """
            SELECT
                count(*) FILTER (
                    WHERE (status = 'PENDING' AND available_at <= :now)
                       OR (status = 'IN_FLIGHT' AND lease_until <= :now)
                ) AS due,
                count(*) FILTER (
                    WHERE status = 'IN_FLIGHT' AND lease_until > :now
                ) AS leased,
                count(*) FILTER (WHERE status = 'FAILED') AS failed,
                COALESCE(
                    EXTRACT(EPOCH FROM (
                        :now - min(created_at) FILTER (WHERE status <> 'PUBLISHED')
                    )),
                    0
                )::bigint AS oldest_age_seconds
            FROM outbox_events
            """)
        .param("now", databaseTimestamp(now))
        .query(
            (resultSet, rowNumber) ->
                new BacklogSnapshot(
                    resultSet.getLong("due"),
                    resultSet.getLong("leased"),
                    resultSet.getLong("failed"),
                    Math.max(0L, resultSet.getLong("oldest_age_seconds"))))
        .single();
  }

  private ExistingEvent mapExisting(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ExistingEvent(
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("topic"),
        resultSet.getString("event_key"),
        resultSet.getString("event_type"),
        resultSet.getInt("schema_version"),
        resultSet.getObject("aggregate_id", UUID.class),
        resultSet.getString("correlation_id"),
        resultSet.getObject("causation_id", UUID.class),
        resultSet.getTimestamp("occurred_at").toInstant(),
        resultSet.getBytes("payload_hash"));
  }

  private OutboxRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
    return new OutboxRecord(
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("topic"),
        resultSet.getString("event_key"),
        resultSet.getString("event_type"),
        resultSet.getInt("schema_version"),
        resultSet.getObject("aggregate_id", UUID.class),
        resultSet.getString("correlation_id"),
        resultSet.getObject("causation_id", UUID.class),
        resultSet.getTimestamp("occurred_at").toInstant(),
        resultSet.getString("payload"),
        resultSet.getBytes("payload_hash"),
        resultSet.getString("traceparent"),
        resultSet.getString("tracestate"),
        resultSet.getString("lease_owner"),
        resultSet.getInt("cycle_attempt_count"),
        resultSet.getLong("total_attempt_count"));
  }

  private OffsetDateTime databaseTimestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private record ExistingEvent(
      UUID eventId,
      String topic,
      String eventKey,
      String eventType,
      int schemaVersion,
      UUID aggregateId,
      String correlationId,
      UUID causationId,
      Instant occurredAt,
      byte[] payloadHash) {

    private boolean matches(
        PaymentCapturedEventV1 event,
        String expectedTopic,
        String expectedKey,
        byte[] expectedHash) {
      return topic.equals(expectedTopic)
          && eventKey.equals(expectedKey)
          && eventType.equals(event.eventType())
          && schemaVersion == event.schemaVersion()
          && aggregateId.equals(event.aggregateId())
          && correlationId.equals(event.correlationId())
          && causationId.equals(event.causationId())
          && occurredAt.equals(event.occurredAt())
          && Arrays.equals(payloadHash, expectedHash);
    }
  }

  public record BacklogSnapshot(long due, long leased, long failed, long oldestAgeSeconds) {}
}
