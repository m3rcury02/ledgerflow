package com.ledgerflow.notifications.internal.persistence;

import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.ReplayNotAvailableException;
import com.ledgerflow.notifications.internal.application.ReplayOwnershipLostException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcNotificationStore {

  private final JdbcClient jdbcClient;

  public JdbcNotificationStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Transactional
  public void process(
      PaymentCapturedEventV1 event,
      byte[] canonicalPayloadHash,
      String topic,
      int partition,
      long offset,
      String processingCorrelationId,
      Instant now) {
    int inserted;
    try {
      inserted =
          jdbcClient
              .sql(
                  """
                  INSERT INTO notification_inbox (
                      event_id, event_type, schema_version, topic, partition_id, offset_value,
                      payload_hash, received_at, processed_at
                  ) VALUES (
                      :eventId, :eventType, :schemaVersion, :topic, :partition, :offset,
                      :payloadHash, :now, :now
                  )
                  ON CONFLICT (event_id) DO NOTHING
                  """)
              .param("eventId", event.eventId())
              .param("eventType", event.eventType())
              .param("schemaVersion", event.schemaVersion())
              .param("topic", topic)
              .param("partition", partition)
              .param("offset", offset)
              .param("payloadHash", canonicalPayloadHash)
              .param("now", databaseTimestamp(now))
              .update();
    } catch (DataIntegrityViolationException exception) {
      throw new NotificationIntegrityException(
          "Kafka coordinate is already associated with a different event", exception);
    }

    if (inserted == 0) {
      byte[] existingHash =
          jdbcClient
              .sql("SELECT payload_hash FROM notification_inbox WHERE event_id = :eventId")
              .param("eventId", event.eventId())
              .query(byte[].class)
              .single();
      if (!Arrays.equals(existingHash, canonicalPayloadHash)) {
        throw new NotificationIntegrityException(
            "Kafka event ID is already associated with different canonical content");
      }
      return;
    }

    PaymentCapturedDataV1 data = event.data();
    jdbcClient
        .sql(
            """
            INSERT INTO notifications (
                event_id, order_id, payment_id, type, status, amount_minor, currency,
                business_correlation_id, processing_correlation_id, created_at
            ) VALUES (
                :eventId, :orderId, :paymentId, 'PAYMENT_CAPTURED', 'CREATED',
                :amountMinor, :currency, :businessCorrelationId, :processingCorrelationId, :now
            )
            """)
        .param("eventId", event.eventId())
        .param("orderId", data.orderId())
        .param("paymentId", data.paymentId())
        .param("amountMinor", data.amountMinor())
        .param("currency", data.currency())
        .param("businessCorrelationId", event.correlationId())
        .param("processingCorrelationId", processingCorrelationId)
        .param("now", databaseTimestamp(now))
        .update();
  }

  @Transactional
  public void catalog(DeadLetterCatalogEntry entry) {
    int inserted;
    try {
      inserted =
          jdbcClient
              .sql(
                  """
                  INSERT INTO dead_letter_records (
                      event_id, consumer_name, original_topic, original_partition,
                      original_offset, event_key, validated_payload, payload_hash, payload_size,
                      safe_headers, failure_code, failure_summary, attempt_count, replayable,
                      replay_available_at, dead_lettered_at
                  ) VALUES (
                      :eventId, :consumerName, :originalTopic, :originalPartition,
                      :originalOffset, :eventKey, CAST(:validatedPayload AS jsonb),
                      :payloadHash, :payloadSize, CAST(:safeHeaders AS jsonb), :failureCode,
                      :failureSummary, :attemptCount, :replayable, :replayAvailableAt,
                      :deadLetteredAt
                  )
                  ON CONFLICT (consumer_name, original_topic, original_partition, original_offset)
                  DO NOTHING
                  """)
              .param("eventId", entry.eventId(), Types.OTHER)
              .param("consumerName", entry.consumerName())
              .param("originalTopic", entry.originalTopic())
              .param("originalPartition", entry.originalPartition())
              .param("originalOffset", entry.originalOffset())
              .param("eventKey", entry.eventKey(), Types.VARCHAR)
              .param("validatedPayload", entry.validatedPayload(), Types.VARCHAR)
              .param("payloadHash", entry.payloadHash())
              .param("payloadSize", entry.payloadSize())
              .param("safeHeaders", entry.safeHeaders())
              .param("failureCode", entry.failureCode())
              .param("failureSummary", entry.failureSummary())
              .param("attemptCount", entry.attemptCount())
              .param("replayable", entry.replayable())
              .param(
                  "replayAvailableAt",
                  entry.replayable() ? databaseTimestamp(entry.catalogedAt()) : null,
                  Types.TIMESTAMP_WITH_TIMEZONE)
              .param("deadLetteredAt", databaseTimestamp(entry.catalogedAt()))
              .update();
    } catch (DataIntegrityViolationException exception) {
      throw new NotificationIntegrityException(
          "Dead-letter catalog insert was rejected", exception);
    }
    if (inserted == 0) {
      ExistingCatalogRecord existing =
          jdbcClient
              .sql(
                  """
                  SELECT payload_hash, event_id, event_key, replayable
                  FROM dead_letter_records
                  WHERE consumer_name = :consumerName
                    AND original_topic = :originalTopic
                    AND original_partition = :originalPartition
                    AND original_offset = :originalOffset
                  """)
              .param("consumerName", entry.consumerName())
              .param("originalTopic", entry.originalTopic())
              .param("originalPartition", entry.originalPartition())
              .param("originalOffset", entry.originalOffset())
              .query(this::mapExistingCatalogRecord)
              .single();
      if (!existing.matches(entry)) {
        throw new NotificationIntegrityException(
            "Dead-letter coordinate is already associated with different content");
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReplayClaim claimReplay(
      UUID deadLetterRecordId,
      UUID replayRequestId,
      String leaseOwner,
      String actor,
      String reason,
      String correlationId,
      Instant now,
      Duration leaseDuration) {
    ReplayClaim claim =
        jdbcClient
            .sql(
                """
                WITH candidate AS (
                    SELECT id
                    FROM dead_letter_records
                    WHERE id = :recordId
                      AND replayable
                      AND (
                          (status = 'OPEN'
                              AND (replay_available_at IS NULL OR replay_available_at <= :now))
                          OR (status = 'REPLAYING' AND replay_lease_until <= :now)
                      )
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE dead_letter_records d
                SET status = 'REPLAYING', replay_count = replay_count + 1,
                    replay_available_at = :now, replay_lease_owner = :leaseOwner,
                    replay_lease_until = :leaseUntil, last_replay_failure_code = NULL,
                    last_replay_failed_at = NULL, version = version + 1
                FROM candidate c
                WHERE d.id = c.id
                RETURNING d.id, d.event_id, d.event_key, d.validated_payload::text AS payload
                """)
            .param("recordId", deadLetterRecordId)
            .param("now", databaseTimestamp(now))
            .param("leaseOwner", leaseOwner)
            .param("leaseUntil", databaseTimestamp(now.plus(leaseDuration)))
            .query(
                (resultSet, rowNumber) ->
                    new ReplayClaim(
                        resultSet.getObject("id", UUID.class),
                        replayRequestId,
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_key"),
                        resultSet.getString("payload"),
                        leaseOwner,
                        actor,
                        reason,
                        correlationId))
            .optional()
            .orElseThrow(ReplayNotAvailableException::new);
    appendAudit(claim, "REQUESTED", null, null, now);
    return claim;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReplayPublished(ReplayClaim claim, Instant now) {
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE dead_letter_records
                SET status = 'REPLAYED', replay_lease_owner = NULL, replay_lease_until = NULL,
                    replayed_at = :now, version = version + 1
                WHERE id = :recordId AND status = 'REPLAYING'
                  AND replay_lease_owner = :leaseOwner AND replay_lease_until >= :now
                """)
            .param("recordId", claim.deadLetterRecordId())
            .param("leaseOwner", claim.leaseOwner())
            .param("now", databaseTimestamp(now))
            .update();
    if (updated != 1) {
      throw new ReplayOwnershipLostException();
    }
    appendAudit(claim, "PUBLISHED", null, null, now);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReplayFailed(
      ReplayClaim claim, String failureCode, String failureSummary, Instant now) {
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE dead_letter_records
                SET status = 'OPEN', replay_available_at = :now,
                    replay_lease_owner = NULL, replay_lease_until = NULL,
                    last_replay_failure_code = :failureCode, last_replay_failed_at = :now,
                    version = version + 1
                WHERE id = :recordId AND status = 'REPLAYING'
                  AND replay_lease_owner = :leaseOwner AND replay_lease_until >= :now
                """)
            .param("recordId", claim.deadLetterRecordId())
            .param("leaseOwner", claim.leaseOwner())
            .param("failureCode", failureCode)
            .param("now", databaseTimestamp(now))
            .update();
    if (updated != 1) {
      throw new ReplayOwnershipLostException();
    }
    appendAudit(claim, "FAILED", failureCode, failureSummary, now);
  }

  private void appendAudit(
      ReplayClaim claim, String action, String failureCode, String failureSummary, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO message_replay_audit (
                replay_request_id, dead_letter_record_id, actor, reason, action,
                correlation_id, failure_code, failure_summary, occurred_at
            ) VALUES (
                :requestId, :recordId, :actor, :reason, :action,
                :correlationId, :failureCode, :failureSummary, :now
            )
            """)
        .param("requestId", claim.replayRequestId())
        .param("recordId", claim.deadLetterRecordId())
        .param("actor", claim.actor())
        .param("reason", claim.reason())
        .param("action", action)
        .param("correlationId", claim.correlationId())
        .param("failureCode", failureCode, Types.VARCHAR)
        .param("failureSummary", failureSummary, Types.VARCHAR)
        .param("now", databaseTimestamp(now))
        .update();
  }

  private ExistingCatalogRecord mapExistingCatalogRecord(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ExistingCatalogRecord(
        resultSet.getBytes("payload_hash"),
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("event_key"),
        resultSet.getBoolean("replayable"));
  }

  private OffsetDateTime databaseTimestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private record ExistingCatalogRecord(
      byte[] payloadHash, UUID eventId, String eventKey, boolean replayable) {

    private boolean matches(DeadLetterCatalogEntry entry) {
      return Arrays.equals(payloadHash, entry.payloadHash())
          && java.util.Objects.equals(eventId, entry.eventId())
          && java.util.Objects.equals(eventKey, entry.eventKey())
          && replayable == entry.replayable();
    }
  }
}
