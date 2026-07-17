package com.ledgerflow.notifications.internal.persistence;

import com.ledgerflow.messaging.api.PaymentCapturedDataV1;
import com.ledgerflow.messaging.api.PaymentCapturedEventV1;
import com.ledgerflow.notifications.internal.application.NotificationEffectIdentity;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationSemanticConflictException;
import com.ledgerflow.notifications.internal.application.ReplayNotAvailableException;
import com.ledgerflow.notifications.internal.application.ReplayOwnershipLostException;
import com.ledgerflow.operations.api.RecoveryLeaseGuard;
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
  public NotificationProcessOutcome process(
      PaymentCapturedEventV1 event,
      NotificationEffectIdentity effect,
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
                      payload_hash, processing_outcome, received_at, processed_at
                  ) VALUES (
                      :eventId, :eventType, :schemaVersion, :topic, :partition, :offset,
                      :payloadHash, 'APPLIED', :now, :now
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
      return NotificationProcessOutcome.TRANSPORT_DUPLICATE;
    }

    PaymentCapturedDataV1 data = event.data();
    int notificationInserted =
        jdbcClient
            .sql(
                """
            INSERT INTO notifications (
                event_id, order_id, payment_id, type, status, amount_minor, currency,
                business_correlation_id, processing_correlation_id, created_at,
                effect_type, effect_identity_version, effect_key,
                source_causation_id, source_occurred_at
            ) VALUES (
                :eventId, :orderId, :paymentId, 'PAYMENT_CAPTURED', 'CREATED',
                :amountMinor, :currency, :businessCorrelationId, :processingCorrelationId, :now,
                :effectType, :effectVersion, :effectKey, :sourceCausationId, :sourceOccurredAt
            )
            ON CONFLICT ON CONSTRAINT notification_semantic_effect_unique DO NOTHING
            """)
            .param("eventId", event.eventId())
            .param("orderId", data.orderId())
            .param("paymentId", data.paymentId())
            .param("amountMinor", data.amountMinor())
            .param("currency", data.currency())
            .param("businessCorrelationId", event.correlationId())
            .param("processingCorrelationId", processingCorrelationId)
            .param("now", databaseTimestamp(now))
            .param("effectType", effect.effectType())
            .param("effectVersion", effect.version())
            .param("effectKey", effect.effectKey())
            .param("sourceCausationId", effect.sourceCausationId())
            .param("sourceOccurredAt", databaseTimestamp(effect.sourceOccurredAt()))
            .update();
    if (notificationInserted == 1) {
      return NotificationProcessOutcome.APPLIED;
    }

    ExistingNotificationEffect existing = findEffect(effect);
    if (!existing.matches(effect)) {
      throw new NotificationSemanticConflictException(
          "Notification semantic identity is associated with conflicting content");
    }
    jdbcClient
        .sql(
            """
            UPDATE notification_inbox
            SET processing_outcome = 'SEMANTIC_DUPLICATE'
            WHERE event_id = :eventId
            """)
        .param("eventId", event.eventId())
        .update();
    return NotificationProcessOutcome.SEMANTIC_DUPLICATE;
  }

  @Transactional
  public CatalogWriteOutcome catalogTerminal(TerminalDltRecord entry) {
    int inserted;
    try {
      inserted =
          jdbcClient
              .sql(
                  """
                  INSERT INTO terminal_dlt_records (
                      consumer_name, dlt_topic, dlt_partition, dlt_offset,
                      key_hash, key_size, payload_hash, payload_size, safe_headers,
                      failure_code, failure_summary, observed_at
                  ) VALUES (
                      :consumerName, :dltTopic, :dltPartition, :dltOffset,
                      :keyHash, :keySize, :payloadHash, :payloadSize, CAST(:safeHeaders AS jsonb),
                      :failureCode, :failureSummary, :observedAt
                  )
                  ON CONFLICT (consumer_name, dlt_topic, dlt_partition, dlt_offset) DO NOTHING
                  """)
              .param("consumerName", entry.consumerName())
              .param("dltTopic", entry.dltTopic())
              .param("dltPartition", entry.dltPartition())
              .param("dltOffset", entry.dltOffset())
              .param("keyHash", entry.keyHash())
              .param("keySize", entry.keySize())
              .param("payloadHash", entry.payloadHash())
              .param("payloadSize", entry.payloadSize())
              .param("safeHeaders", entry.safeHeaders())
              .param("failureCode", entry.failureCode())
              .param("failureSummary", entry.failureSummary())
              .param("observedAt", databaseTimestamp(entry.observedAt()))
              .update();
    } catch (DataIntegrityViolationException exception) {
      throw new NotificationIntegrityException(
          "Terminal DLT evidence insert was rejected", exception);
    }
    if (inserted == 1) {
      return CatalogWriteOutcome.INSERTED;
    }

    ExistingTerminalRecord existing =
        jdbcClient
            .sql(
                """
                SELECT key_hash, key_size, payload_hash, payload_size,
                       safe_headers = CAST(:safeHeaders AS jsonb) AS safe_headers_match,
                       failure_code, failure_summary
                FROM terminal_dlt_records
                WHERE consumer_name = :consumerName
                  AND dlt_topic = :dltTopic
                  AND dlt_partition = :dltPartition
                  AND dlt_offset = :dltOffset
                """)
            .param("consumerName", entry.consumerName())
            .param("dltTopic", entry.dltTopic())
            .param("dltPartition", entry.dltPartition())
            .param("dltOffset", entry.dltOffset())
            .param("safeHeaders", entry.safeHeaders())
            .query(this::mapExistingTerminalRecord)
            .single();
    if (!existing.matches(entry)) {
      throw new NotificationIntegrityException(
          "Terminal DLT coordinate is associated with conflicting evidence");
    }
    return CatalogWriteOutcome.DUPLICATE;
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
      String correlationId,
      Instant now,
      Duration leaseDuration,
      RecoveryLeaseGuard recoveryLeaseGuard) {
    recoveryLeaseGuard.requireCurrent();
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
                        "operator-recovery-worker",
                        "Executed an authenticated operator recovery command.",
                        correlationId))
            .optional()
            .orElseThrow(ReplayNotAvailableException::new);
    appendAudit(claim, "REQUESTED", null, null, now);
    return claim;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReplayPublished(
      ReplayClaim claim, Instant now, RecoveryLeaseGuard recoveryLeaseGuard) {
    recoveryLeaseGuard.requireCurrent();
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
      ReplayClaim claim,
      String failureCode,
      String failureSummary,
      Instant now,
      RecoveryLeaseGuard recoveryLeaseGuard) {
    recoveryLeaseGuard.requireCurrent();
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
                correlation_id, failure_code, failure_summary, occurred_at, identity_source
            ) VALUES (
                :requestId, :recordId, :actor, :reason, :action,
                :correlationId, :failureCode, :failureSummary, :now, 'TRUSTED_WORKLOAD'
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

  private ExistingNotificationEffect findEffect(NotificationEffectIdentity effect) {
    return jdbcClient
        .sql(
            """
            SELECT order_id, payment_id, source_causation_id, amount_minor, currency,
                   source_occurred_at
            FROM notifications
            WHERE effect_type = :effectType
              AND effect_identity_version = :effectVersion
              AND effect_key = :effectKey
            """)
        .param("effectType", effect.effectType())
        .param("effectVersion", effect.version())
        .param("effectKey", effect.effectKey())
        .query(this::mapExistingNotificationEffect)
        .single();
  }

  private ExistingNotificationEffect mapExistingNotificationEffect(
      ResultSet resultSet, int rowNumber) throws SQLException {
    return new ExistingNotificationEffect(
        resultSet.getObject("order_id", UUID.class),
        resultSet.getObject("payment_id", UUID.class),
        resultSet.getObject("source_causation_id", UUID.class),
        resultSet.getLong("amount_minor"),
        resultSet.getString("currency"),
        resultSet.getObject("source_occurred_at", OffsetDateTime.class).toInstant());
  }

  private ExistingTerminalRecord mapExistingTerminalRecord(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ExistingTerminalRecord(
        resultSet.getBytes("key_hash"),
        resultSet.getInt("key_size"),
        resultSet.getBytes("payload_hash"),
        resultSet.getInt("payload_size"),
        resultSet.getBoolean("safe_headers_match"),
        resultSet.getString("failure_code"),
        resultSet.getString("failure_summary"));
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

  private record ExistingNotificationEffect(
      UUID orderId,
      UUID paymentId,
      UUID sourceCausationId,
      long amountMinor,
      String currency,
      Instant sourceOccurredAt) {

    private boolean matches(NotificationEffectIdentity effect) {
      return orderId.equals(effect.orderId())
          && paymentId.equals(effect.paymentId())
          && sourceCausationId.equals(effect.sourceCausationId())
          && amountMinor == effect.amountMinor()
          && currency.equals(effect.currency())
          && sourceOccurredAt.equals(effect.sourceOccurredAt());
    }
  }

  private record ExistingTerminalRecord(
      byte[] keyHash,
      int keySize,
      byte[] payloadHash,
      int payloadSize,
      boolean safeHeadersMatch,
      String failureCode,
      String failureSummary) {

    private boolean matches(TerminalDltRecord entry) {
      return Arrays.equals(keyHash, entry.keyHash())
          && keySize == entry.keySize()
          && Arrays.equals(payloadHash, entry.payloadHash())
          && payloadSize == entry.payloadSize()
          && safeHeadersMatch
          && failureCode.equals(entry.failureCode())
          && failureSummary.equals(entry.failureSummary());
    }
  }
}
