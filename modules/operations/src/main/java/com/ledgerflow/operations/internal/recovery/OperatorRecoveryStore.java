package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class OperatorRecoveryStore {

  private static final String FAILURE_UNION =
      """
      SELECT 'PAYMENT' AS operation_type, p.id AS source_id, p.state AS status,
             p.failure_code,
             CASE
               WHEN p.state = 'FAILED' THEN 'Payment failed protocol validation.'
               WHEN p.state LIKE '%UNKNOWN' THEN 'Payment provider outcome requires reconciliation.'
               ELSE 'Payment provider retry budget was exhausted.'
             END AS summary,
             (p.authorization_attempt_count + p.capture_attempt_count)::bigint AS attempt_count,
             p.updated_at AS failed_at, p.updated_at,
             (p.state IN (
               'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
               'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN'
             )) AS retryable,
             NULL::varchar AS origin_traceparent
      FROM payments p
      WHERE p.state IN (
        'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
        'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'FAILED'
      )
      UNION ALL
      SELECT 'OUTBOX', o.event_id, o.status, o.last_failure_code,
             'Outbox publication exhausted its bounded delivery cycle.',
             o.total_attempt_count, COALESCE(o.last_failed_at, o.created_at),
             COALESCE(o.last_failed_at, o.created_at),
             (o.status = 'FAILED'), o.traceparent
      FROM outbox_events o
      WHERE o.status = 'FAILED'
      UNION ALL
      SELECT 'DEAD_LETTER', d.id, d.status, d.failure_code,
             CASE WHEN d.replayable
               THEN 'Validated dead-letter evidence is available for controlled replay.'
               ELSE 'Dead-letter evidence is terminal and cannot be replayed.'
             END,
             (d.attempt_count + d.replay_count)::bigint,
             d.dead_lettered_at, COALESCE(d.last_replay_failed_at, d.dead_lettered_at),
             (d.replayable AND d.status = 'OPEN'),
             d.safe_headers ->> 'traceparent'
      FROM dead_letter_records d
      WHERE d.status IN ('OPEN', 'REPLAYING')
      """;

  private final JdbcClient jdbcClient;

  OperatorRecoveryStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  List<FailedOperation> listFailures(int limit, OperationType type, FailureCursor cursor) {
    String sql =
        """
        WITH failures AS (
        %s
        )
        SELECT * FROM failures
        WHERE (:operationType IS NULL OR operation_type = :operationType)
          AND (
            CAST(:cursorTime AS timestamptz) IS NULL
            OR (failed_at, operation_type, source_id) < (
              CAST(:cursorTime AS timestamptz), :cursorType, CAST(:cursorId AS uuid)
            )
          )
        ORDER BY failed_at DESC, operation_type DESC, source_id DESC
        LIMIT :limit
        """
            .formatted(FAILURE_UNION);
    return jdbcClient
        .sql(sql)
        .param("operationType", type == null ? null : type.name(), Types.VARCHAR)
        .param(
            "cursorTime",
            cursor == null ? null : timestamp(cursor.failedAt()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .param("cursorType", cursor == null ? null : cursor.type().name(), Types.VARCHAR)
        .param("cursorId", cursor == null ? null : cursor.sourceId(), Types.OTHER)
        .param("limit", limit)
        .query(this::mapFailure)
        .list();
  }

  Optional<FailedOperation> findOperation(OperationReference reference) {
    String sql =
        switch (reference.type()) {
          case PAYMENT ->
              """
              SELECT 'PAYMENT' AS operation_type, p.id AS source_id, p.state AS status,
                     p.failure_code,
                     CASE
                       WHEN p.state = 'FAILED' THEN 'Payment failed protocol validation.'
                       WHEN p.state IN ('AUTHORIZATION_UNKNOWN', 'CAPTURE_UNKNOWN')
                         THEN 'Payment provider outcome requires reconciliation.'
                       WHEN p.state IN ('AUTHORIZATION_RETRY_PENDING', 'CAPTURE_RETRY_PENDING')
                         THEN 'Payment provider retry budget was exhausted.'
                       ELSE 'Payment recovery is no longer required.'
                     END AS summary,
                     (p.authorization_attempt_count + p.capture_attempt_count)::bigint
                       AS attempt_count,
                     p.updated_at AS failed_at, p.updated_at,
                     (p.state IN (
                       'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
                       'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN'
                     )) AS retryable,
                     NULL::varchar AS origin_traceparent
              FROM payments p
              WHERE p.id = :sourceId
                AND p.state IN (
                  'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
                  'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'FAILED'
                )
              """;
          case OUTBOX ->
              """
              SELECT 'OUTBOX' AS operation_type, o.event_id AS source_id, o.status,
                     o.last_failure_code AS failure_code,
                     CASE WHEN o.status = 'FAILED'
                       THEN 'Outbox publication exhausted its bounded delivery cycle.'
                       ELSE 'Outbox recovery is no longer required.'
                     END AS summary,
                     o.total_attempt_count AS attempt_count,
                     COALESCE(o.last_failed_at, o.created_at) AS failed_at,
                     COALESCE(o.published_at, o.last_failed_at, o.created_at) AS updated_at,
                     (o.status = 'FAILED') AS retryable,
                     o.traceparent AS origin_traceparent
              FROM outbox_events o
              WHERE o.event_id = :sourceId AND o.status = 'FAILED'
              """;
          case DEAD_LETTER ->
              """
              SELECT 'DEAD_LETTER' AS operation_type, d.id AS source_id, d.status,
                     d.failure_code,
                     CASE
                       WHEN NOT d.replayable
                         THEN 'Dead-letter evidence is terminal and cannot be replayed.'
                       WHEN d.status = 'OPEN'
                         THEN 'Validated dead-letter evidence is available for controlled replay.'
                       ELSE 'Dead-letter replay is no longer required.'
                     END AS summary,
                     (d.attempt_count + d.replay_count)::bigint AS attempt_count,
                     d.dead_lettered_at AS failed_at,
                     COALESCE(d.replayed_at, d.last_replay_failed_at, d.dead_lettered_at)
                       AS updated_at,
                     (d.replayable AND d.status = 'OPEN') AS retryable,
                     d.safe_headers ->> 'traceparent' AS origin_traceparent
              FROM dead_letter_records d
              WHERE d.id = :sourceId AND d.status IN ('OPEN', 'REPLAYING')
              """;
        };
    return jdbcClient
        .sql(sql)
        .param("sourceId", reference.sourceId())
        .query(this::mapFailure)
        .optional();
  }

  List<OperationAttempt> attempts(OperationReference reference) {
    List<OperationAttempt> attempts = new ArrayList<>();
    switch (reference.type()) {
      case PAYMENT ->
          attempts.addAll(
              jdbcClient
                  .sql(
                      """
                      SELECT 'PAYMENT_PROVIDER' AS source, activity AS action, attempt_number,
                             outcome, failure_code, recorded_at
                      FROM payment_attempt_history
                      WHERE payment_id = :sourceId
                      ORDER BY recorded_at, id
                      LIMIT 100
                      """)
                  .param("sourceId", reference.sourceId())
                  .query(this::mapAttempt)
                  .list());
      case OUTBOX -> {
        Optional<OperationAttempt> outbox =
            jdbcClient
                .sql(
                    """
                    SELECT 'OUTBOX' AS source, 'PUBLISH' AS action,
                           LEAST(total_attempt_count, 2147483647)::integer AS attempt_number,
                           status AS outcome, last_failure_code AS failure_code,
                           COALESCE(published_at, last_failed_at, created_at) AS recorded_at
                    FROM outbox_events WHERE event_id = :sourceId
                    """)
                .param("sourceId", reference.sourceId())
                .query(this::mapAttempt)
                .optional();
        outbox.ifPresent(attempts::add);
      }
      case DEAD_LETTER ->
          attempts.addAll(
              jdbcClient
                  .sql(
                      """
                      SELECT 'LEGACY_REPLAY' AS source, action,
                             row_number() OVER (
                               ORDER BY occurred_at, id
                             )::integer AS attempt_number,
                             action AS outcome, failure_code, occurred_at AS recorded_at
                      FROM message_replay_audit
                      WHERE dead_letter_record_id = :sourceId
                      ORDER BY occurred_at, id
                      LIMIT 100
                      """)
                  .param("sourceId", reference.sourceId())
                  .query(this::mapAttempt)
                  .list());
    }
    attempts.addAll(operatorAttempts(reference));
    return attempts.stream().sorted(Comparator.comparing(OperationAttempt::at)).limit(200).toList();
  }

  @Transactional
  RecoveryState lockRecoveryState(OperationReference reference, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO operator_recovery_state (
                operation_type, source_id, created_at, updated_at
            ) VALUES (:operationType, :sourceId, :now, :now)
            ON CONFLICT (operation_type, source_id) DO NOTHING
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .param("now", timestamp(now))
        .update();
    return jdbcClient
        .sql(
            """
            SELECT automatic_attempt_count, break_glass_attempt_count,
                   retry_available_at, version
            FROM operator_recovery_state
            WHERE operation_type = :operationType AND source_id = :sourceId
            FOR UPDATE
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .query(
            (rs, row) ->
                new RecoveryState(
                    rs.getInt("automatic_attempt_count"),
                    rs.getInt("break_glass_attempt_count"),
                    instant(rs, "retry_available_at"),
                    rs.getLong("version")))
        .single();
  }

  Optional<RetryCommand> findRetryByKey(ActorIdentity actor, byte[] keyHash) {
    return jdbcClient
        .sql(
            """
            SELECT * FROM operator_retry_commands
            WHERE actor_issuer = :issuer AND actor_subject = :subject
              AND idempotency_key_hash = :keyHash
            """)
        .param("issuer", actor.issuer())
        .param("subject", actor.subject())
        .param("keyHash", keyHash)
        .query(this::mapCommand)
        .optional();
  }

  void lockIdempotencyKey(byte[] keyHash) {
    long lockKey = ByteBuffer.wrap(keyHash, 0, Long.BYTES).getLong();
    jdbcClient
        .sql("SELECT pg_advisory_xact_lock(:lockKey)")
        .param("lockKey", lockKey)
        .query((rs, row) -> rs.getObject(1))
        .single();
  }

  Optional<RetryCommand> findActive(OperationReference reference) {
    return jdbcClient
        .sql(
            """
            SELECT * FROM operator_retry_commands
            WHERE operation_type = :operationType AND source_id = :sourceId
              AND status IN ('PENDING', 'IN_PROGRESS', 'WAITING')
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .query(this::mapCommand)
        .optional();
  }

  RetryCommand insertRetry(
      OperationReference reference,
      byte[] keyHash,
      byte[] requestHash,
      String attemptKind,
      int attemptNumber,
      UUID approvalId,
      String reason,
      ActorIdentity actor,
      String correlationId,
      String requestTraceparent,
      String originTraceparent,
      Instant now) {
    return jdbcClient
        .sql(
            """
            INSERT INTO operator_retry_commands (
                operation_type, source_id, idempotency_key_hash, request_hash,
                attempt_kind, attempt_number, approval_id, reason,
                actor_issuer, actor_subject, actor_client_id,
                request_correlation_id, request_traceparent, origin_traceparent, accepted_at
            ) VALUES (
                :operationType, :sourceId, :keyHash, :requestHash,
                :attemptKind, :attemptNumber, :approvalId, :reason,
                :issuer, :subject, :clientId,
                :correlationId, :requestTraceparent, :originTraceparent, :now
            )
            RETURNING *
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .param("keyHash", keyHash)
        .param("requestHash", requestHash)
        .param("attemptKind", attemptKind)
        .param("attemptNumber", attemptNumber)
        .param("approvalId", approvalId, Types.OTHER)
        .param("reason", reason)
        .param("issuer", actor.issuer())
        .param("subject", actor.subject())
        .param("clientId", actor.clientId(), Types.VARCHAR)
        .param("correlationId", correlationId)
        .param("requestTraceparent", requestTraceparent, Types.VARCHAR)
        .param("originTraceparent", validTraceparent(originTraceparent), Types.VARCHAR)
        .param("now", timestamp(now))
        .query(this::mapCommand)
        .single();
  }

  void reserveAttempt(
      OperationReference reference,
      RetryCommand command,
      int automaticAttempts,
      int breakGlassAttempts,
      Instant now) {
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE operator_recovery_state
                SET automatic_attempt_count = :automaticAttempts,
                    break_glass_attempt_count = :breakGlassAttempts,
                    last_command_id = :commandId,
                    version = version + 1,
                    updated_at = :now
                WHERE operation_type = :operationType AND source_id = :sourceId
                """)
            .param("automaticAttempts", automaticAttempts)
            .param("breakGlassAttempts", breakGlassAttempts)
            .param("commandId", command.id())
            .param("now", timestamp(now))
            .param("operationType", reference.type().name())
            .param("sourceId", reference.sourceId())
            .update();
    requireSingle(updated);
  }

  Optional<BreakGlassApproval> findApprovalByKey(ActorIdentity actor, byte[] keyHash) {
    return jdbcClient
        .sql(
            """
            SELECT id, request_hash, created_at
            FROM operator_break_glass_approvals
            WHERE actor_issuer = :issuer AND actor_subject = :subject
              AND idempotency_key_hash = :keyHash
            """)
        .param("issuer", actor.issuer())
        .param("subject", actor.subject())
        .param("keyHash", keyHash)
        .query(
            (rs, row) ->
                new BreakGlassApproval(
                    rs.getObject("id", UUID.class),
                    rs.getBytes("request_hash"),
                    rs.getTimestamp("created_at").toInstant()))
        .optional();
  }

  BreakGlassApproval insertApproval(
      OperationReference reference,
      byte[] keyHash,
      byte[] requestHash,
      String approvalReference,
      String reason,
      ActorIdentity actor,
      String correlationId,
      String requestTraceparent,
      Instant now) {
    return jdbcClient
        .sql(
            """
            INSERT INTO operator_break_glass_approvals (
                operation_type, source_id, idempotency_key_hash, request_hash,
                approval_reference, reason, actor_issuer, actor_subject, actor_client_id,
                request_correlation_id, request_traceparent, created_at
            ) VALUES (
                :operationType, :sourceId, :keyHash, :requestHash,
                :approvalReference, :reason, :issuer, :subject, :clientId,
                :correlationId, :requestTraceparent, :now
            )
            RETURNING id, request_hash, created_at
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .param("keyHash", keyHash)
        .param("requestHash", requestHash)
        .param("approvalReference", approvalReference)
        .param("reason", reason)
        .param("issuer", actor.issuer())
        .param("subject", actor.subject())
        .param("clientId", actor.clientId(), Types.VARCHAR)
        .param("correlationId", correlationId)
        .param("requestTraceparent", requestTraceparent, Types.VARCHAR)
        .param("now", timestamp(now))
        .query(
            (rs, row) ->
                new BreakGlassApproval(
                    rs.getObject("id", UUID.class),
                    rs.getBytes("request_hash"),
                    rs.getTimestamp("created_at").toInstant()))
        .single();
  }

  void consumeApproval(UUID approvalId, OperationReference reference, UUID commandId, Instant now) {
    int inserted =
        jdbcClient
            .sql(
                """
                INSERT INTO operator_break_glass_uses (approval_id, retry_command_id, used_at)
                SELECT a.id, :commandId, :now
                FROM operator_break_glass_approvals a
                WHERE a.id = :approvalId
                  AND a.operation_type = :operationType AND a.source_id = :sourceId
                  AND NOT EXISTS (
                    SELECT 1 FROM operator_break_glass_uses u WHERE u.approval_id = a.id
                  )
                """)
            .param("commandId", commandId)
            .param("now", timestamp(now))
            .param("approvalId", approvalId)
            .param("operationType", reference.type().name())
            .param("sourceId", reference.sourceId())
            .update();
    if (inserted != 1) {
      throw new OperatorConflictException(OperatorConflictException.Code.BREAK_GLASS_APPROVAL_USED);
    }
  }

  boolean approvalAvailable(UUID approvalId, OperationReference reference) {
    return jdbcClient
            .sql(
                """
                SELECT count(*)
                FROM operator_break_glass_approvals a
                WHERE a.id = :approvalId
                  AND a.operation_type = :operationType AND a.source_id = :sourceId
                  AND NOT EXISTS (
                    SELECT 1 FROM operator_break_glass_uses u WHERE u.approval_id = a.id
                  )
                """)
            .param("approvalId", approvalId)
            .param("operationType", reference.type().name())
            .param("sourceId", reference.sourceId())
            .query(Long.class)
            .single()
        == 1L;
  }

  @Transactional
  List<RetryClaim> claimBatch(String owner, int limit, Instant now, Duration leaseDuration) {
    UUID leaseToken = UUID.randomUUID();
    List<RetryClaim> claims =
        jdbcClient
            .sql(
                """
                WITH candidates AS (
                    SELECT id, status AS previous_status, started_at AS previous_started_at
                    FROM operator_retry_commands
                    WHERE status = 'PENDING'
                       OR (status = 'WAITING' AND next_check_at <= :now)
                       OR (status = 'IN_PROGRESS' AND lease_until <= :now)
                    ORDER BY accepted_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                ), updated AS (
                    UPDATE operator_retry_commands c
                    SET status = 'IN_PROGRESS', lease_owner = :owner,
                        lease_token = :leaseToken, lease_until = :leaseUntil,
                        next_check_at = NULL, started_at = COALESCE(c.started_at, :now),
                        version = c.version + 1
                    FROM candidates selected
                    WHERE c.id = selected.id
                    RETURNING c.*, selected.previous_status, selected.previous_started_at
                )
                SELECT * FROM updated ORDER BY accepted_at, id
                """)
            .param("now", timestamp(now))
            .param("limit", limit)
            .param("owner", owner)
            .param("leaseToken", leaseToken)
            .param("leaseUntil", timestamp(now.plus(leaseDuration)))
            .query(
                (rs, row) -> {
                  RetryCommand command = mapCommand(rs, row);
                  String previous = rs.getString("previous_status");
                  return new RetryClaim(
                      command,
                      rs.getTimestamp("previous_started_at") == null,
                      "IN_PROGRESS".equals(previous));
                })
            .list();
    for (RetryClaim claim : claims) {
      appendAttempt(
          claim.command(),
          claim.takeover() ? "TAKEN_OVER" : "CLAIMED",
          claim.takeover() ? "LEASE_TAKEN_OVER" : "LEASE_CLAIMED",
          now);
    }
    return claims;
  }

  boolean leaseIsCurrent(RetryCommand command, Instant now) {
    return jdbcClient
        .sql(
            """
                SELECT id
                FROM operator_retry_commands
                WHERE id = :commandId AND status = 'IN_PROGRESS'
                  AND lease_token = :leaseToken AND lease_owner = :leaseOwner
                  AND version = :version AND lease_until > :now
                FOR SHARE
                """)
        .param("commandId", command.id())
        .param("leaseToken", command.leaseToken())
        .param("leaseOwner", command.leaseOwner())
        .param("version", command.version())
        .param("now", timestamp(now))
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  @Transactional
  boolean markWaiting(RetryCommand command, String resultCode, Instant nextCheckAt, Instant now) {
    int updated =
        guardedUpdate(
            command,
            jdbcClient
                .sql(
                    """
                    UPDATE operator_retry_commands
                    SET status = 'WAITING', lease_owner = NULL, lease_token = NULL,
                        lease_until = NULL, next_check_at = :nextCheckAt,
                        version = version + 1
                    WHERE id = :commandId AND status = 'IN_PROGRESS'
                      AND lease_token = :leaseToken AND lease_owner = :leaseOwner
                      AND version = :version AND lease_until > :now
                    """)
                .param("nextCheckAt", timestamp(nextCheckAt)),
            now);
    if (updated == 1) {
      appendAttempt(command, "WAITING", resultCode, now);
    }
    return updated == 1;
  }

  @Transactional
  boolean markCompleted(RetryCommand command, String resultCode, Instant now) {
    int updated =
        guardedUpdate(
            command,
            jdbcClient
                .sql(
                    """
                    UPDATE operator_retry_commands
                    SET status = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                        lease_until = NULL, result_code = :resultCode,
                        completed_at = :now, version = version + 1
                    WHERE id = :commandId AND status = 'IN_PROGRESS'
                      AND lease_token = :leaseToken AND lease_owner = :leaseOwner
                      AND version = :version AND lease_until > :now
                    """)
                .param("resultCode", resultCode),
            now);
    if (updated == 1) {
      clearCooldown(command, now);
      appendAttempt(command, "COMPLETED", resultCode, now);
      appendAudit(command, "RETRY_COMPLETED", resultCode, now);
    }
    return updated == 1;
  }

  @Transactional
  boolean markFailed(
      RetryCommand command, String failureCode, Instant retryAvailableAt, Instant now) {
    int updated =
        guardedUpdate(
            command,
            jdbcClient
                .sql(
                    """
                    UPDATE operator_retry_commands
                    SET status = 'FAILED', lease_owner = NULL, lease_token = NULL,
                        lease_until = NULL, failure_code = :resultCode,
                        completed_at = :now, version = version + 1
                    WHERE id = :commandId AND status = 'IN_PROGRESS'
                      AND lease_token = :leaseToken AND lease_owner = :leaseOwner
                      AND version = :version AND lease_until > :now
                    """)
                .param("resultCode", failureCode),
            now);
    if (updated == 1) {
      setCooldown(command, retryAvailableAt, now);
      appendAttempt(command, "FAILED", failureCode, now);
      appendAudit(command, "RETRY_FAILED", failureCode, now);
    }
    return updated == 1;
  }

  @Transactional
  void recordStaleCompletion(RetryCommand command, Instant now) {
    appendAttempt(command, "STALE_REJECTED", "STALE_LEASE", now);
    appendAudit(command, "STALE_COMPLETION_REJECTED", "STALE_LEASE", now);
  }

  Optional<RetryCommand> findRetry(OperationReference reference, UUID retryId) {
    return jdbcClient
        .sql(
            """
            SELECT * FROM operator_retry_commands
            WHERE id = :retryId AND operation_type = :operationType AND source_id = :sourceId
            """)
        .param("retryId", retryId)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .query(this::mapCommand)
        .optional();
  }

  OperatorRecoverySnapshot snapshot(Instant now) {
    return jdbcClient
        .sql(
            """
            SELECT
              count(*) FILTER (WHERE status = 'PENDING') AS pending,
              count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
              count(*) FILTER (WHERE status = 'WAITING') AS waiting,
              count(*) FILTER (WHERE status = 'FAILED') AS failed,
              COALESCE(EXTRACT(EPOCH FROM (
                :now - min(accepted_at) FILTER (
                  WHERE status IN ('PENDING', 'IN_PROGRESS', 'WAITING')
                )
              )), 0)::bigint AS oldest_age
            FROM operator_retry_commands
            """)
        .param("now", timestamp(now))
        .query(
            (rs, row) ->
                new OperatorRecoverySnapshot(
                    rs.getLong("pending"),
                    rs.getLong("in_progress"),
                    rs.getLong("waiting"),
                    rs.getLong("failed"),
                    rs.getLong("oldest_age")))
        .single();
  }

  void appendAudit(
      OperationReference reference,
      RetryCommand command,
      BreakGlassApproval approval,
      ActorIdentity actor,
      String action,
      String reason,
      String outcome,
      String correlationId,
      Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO operator_audit_records (
                operation_type, source_id, retry_command_id, approval_id,
                actor_issuer, actor_subject, actor_client_id, identity_source,
                action, reason, outcome_code, correlation_id, occurred_at
            ) VALUES (
                :operationType, :sourceId, :commandId, :approvalId,
                :issuer, :subject, :clientId, 'OIDC_JWT',
                :action, :reason, :outcome, :correlationId, :now
            )
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .param("commandId", command == null ? null : command.id(), Types.OTHER)
        .param("approvalId", approval == null ? null : approval.id(), Types.OTHER)
        .param("issuer", actor.issuer())
        .param("subject", actor.subject())
        .param("clientId", actor.clientId(), Types.VARCHAR)
        .param("action", action)
        .param("reason", reason)
        .param("outcome", outcome)
        .param("correlationId", correlationId)
        .param("now", timestamp(now))
        .update();
  }

  private List<OperationAttempt> operatorAttempts(OperationReference reference) {
    return jdbcClient
        .sql(
            """
            SELECT 'OPERATOR_RETRY' AS source, a.action,
                   c.attempt_number, a.outcome_code AS outcome,
                   CASE WHEN a.action IN ('FAILED', 'STALE_REJECTED')
                     THEN a.outcome_code ELSE NULL END AS failure_code,
                   a.occurred_at AS recorded_at
            FROM operator_retry_attempts a
            JOIN operator_retry_commands c ON c.id = a.retry_command_id
            WHERE c.operation_type = :operationType AND c.source_id = :sourceId
            ORDER BY a.occurred_at, a.id
            LIMIT 100
            """)
        .param("operationType", reference.type().name())
        .param("sourceId", reference.sourceId())
        .query(this::mapAttempt)
        .list();
  }

  private int guardedUpdate(RetryCommand command, JdbcClient.StatementSpec statement, Instant now) {
    return statement
        .param("commandId", command.id())
        .param("leaseToken", command.leaseToken())
        .param("leaseOwner", command.leaseOwner())
        .param("version", command.version())
        .param("now", timestamp(now))
        .update();
  }

  private void clearCooldown(RetryCommand command, Instant now) {
    requireSingle(
        jdbcClient
            .sql(
                """
                UPDATE operator_recovery_state
                SET retry_available_at = NULL, updated_at = :now, version = version + 1
                WHERE operation_type = :operationType AND source_id = :sourceId
                  AND last_command_id = :commandId
                """)
            .param("now", timestamp(now))
            .param("operationType", command.operationType().name())
            .param("sourceId", command.sourceId())
            .param("commandId", command.id())
            .update());
  }

  private void setCooldown(RetryCommand command, Instant retryAvailableAt, Instant now) {
    requireSingle(
        jdbcClient
            .sql(
                """
                UPDATE operator_recovery_state
                SET retry_available_at = :retryAvailableAt,
                    updated_at = :now, version = version + 1
                WHERE operation_type = :operationType AND source_id = :sourceId
                  AND last_command_id = :commandId
                """)
            .param("retryAvailableAt", timestamp(retryAvailableAt))
            .param("now", timestamp(now))
            .param("operationType", command.operationType().name())
            .param("sourceId", command.sourceId())
            .param("commandId", command.id())
            .update());
  }

  private void appendAttempt(RetryCommand command, String action, String outcomeCode, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO operator_retry_attempts (
                retry_command_id, lease_token, action, outcome_code, occurred_at
            ) VALUES (:commandId, :leaseToken, :action, :outcome, :now)
            """)
        .param("commandId", command.id())
        .param("leaseToken", command.leaseToken(), Types.OTHER)
        .param("action", action)
        .param("outcome", outcomeCode)
        .param("now", timestamp(now))
        .update();
  }

  private void appendAudit(RetryCommand command, String action, String outcome, Instant now) {
    appendAudit(
        new OperationReference(command.operationType(), command.sourceId()),
        command,
        null,
        command.actor(),
        action,
        command.reason(),
        outcome,
        command.correlationId(),
        now);
  }

  private FailedOperation mapFailure(ResultSet rs, int row) throws SQLException {
    return new FailedOperation(
        OperationType.valueOf(rs.getString("operation_type")),
        rs.getObject("source_id", UUID.class),
        rs.getString("status"),
        rs.getString("failure_code"),
        rs.getString("summary"),
        rs.getLong("attempt_count"),
        rs.getTimestamp("failed_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getBoolean("retryable"),
        validTraceparent(rs.getString("origin_traceparent")));
  }

  private OperationAttempt mapAttempt(ResultSet rs, int row) throws SQLException {
    return new OperationAttempt(
        rs.getString("source"),
        rs.getString("action"),
        rs.getInt("attempt_number"),
        rs.getString("outcome"),
        rs.getString("failure_code"),
        rs.getTimestamp("recorded_at").toInstant());
  }

  private RetryCommand mapCommand(ResultSet rs, int row) throws SQLException {
    return new RetryCommand(
        rs.getObject("id", UUID.class),
        OperationType.valueOf(rs.getString("operation_type")),
        rs.getObject("source_id", UUID.class),
        rs.getBytes("request_hash"),
        rs.getString("attempt_kind"),
        rs.getInt("attempt_number"),
        rs.getObject("approval_id", UUID.class),
        rs.getString("status"),
        rs.getString("reason"),
        new ActorIdentity(
            rs.getString("actor_issuer"),
            rs.getString("actor_subject"),
            rs.getString("actor_client_id")),
        rs.getString("request_correlation_id"),
        rs.getString("request_traceparent"),
        rs.getString("origin_traceparent"),
        rs.getString("lease_owner"),
        rs.getObject("lease_token", UUID.class),
        instant(rs, "lease_until"),
        instant(rs, "next_check_at"),
        rs.getLong("version"),
        rs.getString("result_code"),
        rs.getString("failure_code"),
        rs.getTimestamp("accepted_at").toInstant(),
        instant(rs, "started_at"),
        instant(rs, "completed_at"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String validTraceparent(String value) {
    return value != null
            && value.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
            && !value.substring(3, 35).equals("0".repeat(32))
            && !value.substring(36, 52).equals("0".repeat(16))
        ? value
        : null;
  }

  private void requireSingle(int count) {
    if (count != 1) {
      throw new IllegalStateException("operator recovery state update did not affect one row");
    }
  }

  record FailureCursor(Instant failedAt, OperationType type, UUID sourceId) {}

  record RecoveryState(
      int automaticAttempts, int breakGlassAttempts, Instant retryAvailableAt, long version) {}
}
