CREATE TABLE operator_recovery_state (
    operation_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    automatic_attempt_count integer NOT NULL DEFAULT 0,
    break_glass_attempt_count integer NOT NULL DEFAULT 0,
    retry_available_at timestamptz,
    last_command_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (operation_type, source_id),
    CONSTRAINT operator_recovery_state_type_valid CHECK (
        operation_type IN ('PAYMENT', 'OUTBOX', 'DEAD_LETTER')
    ),
    CONSTRAINT operator_recovery_state_counts_bounded CHECK (
        automatic_attempt_count BETWEEN 0 AND 1000
        AND break_glass_attempt_count BETWEEN 0 AND 1000
    ),
    CONSTRAINT operator_recovery_state_version_non_negative CHECK (version >= 0),
    CONSTRAINT operator_recovery_state_timestamp_order CHECK (updated_at >= created_at)
);

CREATE TABLE operator_break_glass_approvals (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    operation_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    idempotency_key_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    approval_reference varchar(100) NOT NULL,
    reason varchar(500) NOT NULL,
    actor_issuer varchar(500) NOT NULL,
    actor_subject varchar(200) NOT NULL,
    actor_client_id varchar(200),
    request_correlation_id varchar(64) NOT NULL,
    request_traceparent varchar(55),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT operator_approval_type_valid CHECK (
        operation_type IN ('PAYMENT', 'OUTBOX', 'DEAD_LETTER')
    ),
    CONSTRAINT operator_approval_hashes_sha256 CHECK (
        octet_length(idempotency_key_hash) = 32 AND octet_length(request_hash) = 32
    ),
    CONSTRAINT operator_approval_reference_valid CHECK (
        approval_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{9,99}$'
    ),
    CONSTRAINT operator_approval_reason_valid CHECK (
        char_length(reason) BETWEEN 10 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT operator_approval_actor_valid CHECK (
        char_length(actor_issuer) BETWEEN 1 AND 500
        AND actor_issuer !~ '[[:cntrl:]]'
        AND char_length(actor_subject) BETWEEN 1 AND 200
        AND actor_subject !~ '[[:cntrl:]]'
        AND (actor_client_id IS NULL OR (
            char_length(actor_client_id) BETWEEN 1 AND 200
            AND actor_client_id !~ '[[:cntrl:]]'
        ))
    ),
    CONSTRAINT operator_approval_correlation_valid CHECK (
        request_correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT operator_approval_traceparent_valid CHECK (
        request_traceparent IS NULL OR (
            request_traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
            AND substring(request_traceparent FROM 4 FOR 32) <> repeat('0', 32)
            AND substring(request_traceparent FROM 37 FOR 16) <> repeat('0', 16)
        )
    ),
    CONSTRAINT operator_approval_idempotency_unique UNIQUE (
        actor_issuer, actor_subject, idempotency_key_hash
    )
);

CREATE TABLE operator_retry_commands (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    operation_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    idempotency_key_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    attempt_kind varchar(16) NOT NULL,
    attempt_number integer NOT NULL,
    approval_id uuid REFERENCES operator_break_glass_approvals (id),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    reason varchar(500) NOT NULL,
    actor_issuer varchar(500) NOT NULL,
    actor_subject varchar(200) NOT NULL,
    actor_client_id varchar(200),
    request_correlation_id varchar(64) NOT NULL,
    request_traceparent varchar(55),
    origin_traceparent varchar(55),
    lease_owner varchar(100),
    lease_token uuid,
    lease_until timestamptz,
    next_check_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    result_code varchar(64),
    failure_code varchar(64),
    accepted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    started_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT operator_retry_type_valid CHECK (
        operation_type IN ('PAYMENT', 'OUTBOX', 'DEAD_LETTER')
    ),
    CONSTRAINT operator_retry_hashes_sha256 CHECK (
        octet_length(idempotency_key_hash) = 32 AND octet_length(request_hash) = 32
    ),
    CONSTRAINT operator_retry_attempt_valid CHECK (
        attempt_kind IN ('AUTOMATIC', 'BREAK_GLASS')
        AND attempt_number BETWEEN 1 AND 1000
        AND (
            (attempt_kind = 'AUTOMATIC' AND approval_id IS NULL)
            OR (attempt_kind = 'BREAK_GLASS' AND approval_id IS NOT NULL)
        )
    ),
    CONSTRAINT operator_retry_status_valid CHECK (
        status IN ('PENDING', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT operator_retry_reason_valid CHECK (
        char_length(reason) BETWEEN 10 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT operator_retry_actor_valid CHECK (
        char_length(actor_issuer) BETWEEN 1 AND 500
        AND actor_issuer !~ '[[:cntrl:]]'
        AND char_length(actor_subject) BETWEEN 1 AND 200
        AND actor_subject !~ '[[:cntrl:]]'
        AND (actor_client_id IS NULL OR (
            char_length(actor_client_id) BETWEEN 1 AND 200
            AND actor_client_id !~ '[[:cntrl:]]'
        ))
    ),
    CONSTRAINT operator_retry_correlation_valid CHECK (
        request_correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT operator_retry_request_traceparent_valid CHECK (
        request_traceparent IS NULL OR (
            request_traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
            AND substring(request_traceparent FROM 4 FOR 32) <> repeat('0', 32)
            AND substring(request_traceparent FROM 37 FOR 16) <> repeat('0', 16)
        )
    ),
    CONSTRAINT operator_retry_origin_traceparent_valid CHECK (
        origin_traceparent IS NULL OR (
            origin_traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
            AND substring(origin_traceparent FROM 4 FOR 32) <> repeat('0', 32)
            AND substring(origin_traceparent FROM 37 FOR 16) <> repeat('0', 16)
        )
    ),
    CONSTRAINT operator_retry_lease_owner_valid CHECK (
        lease_owner IS NULL OR lease_owner ~ '^[A-Za-z0-9._:@-]{1,100}$'
    ),
    CONSTRAINT operator_retry_version_non_negative CHECK (version >= 0),
    CONSTRAINT operator_retry_result_code_valid CHECK (
        result_code IS NULL OR result_code ~ '^[A-Z0-9_]{1,64}$'
    ),
    CONSTRAINT operator_retry_failure_code_valid CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]{1,64}$'
    ),
    CONSTRAINT operator_retry_status_shape CHECK (
        (status = 'PENDING'
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND next_check_at IS NULL AND started_at IS NULL AND completed_at IS NULL
            AND result_code IS NULL AND failure_code IS NULL)
        OR
        (status = 'IN_PROGRESS'
            AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND next_check_at IS NULL AND started_at IS NOT NULL AND completed_at IS NULL
            AND result_code IS NULL AND failure_code IS NULL)
        OR
        (status = 'WAITING'
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND next_check_at IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NULL
            AND result_code IS NULL AND failure_code IS NULL)
        OR
        (status = 'COMPLETED'
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND next_check_at IS NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND result_code IS NOT NULL AND failure_code IS NULL)
        OR
        (status = 'FAILED'
            AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND next_check_at IS NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND result_code IS NULL AND failure_code IS NOT NULL)
    ),
    CONSTRAINT operator_retry_timestamp_order CHECK (
        (started_at IS NULL OR started_at >= accepted_at)
        AND (lease_until IS NULL OR lease_until > started_at)
        AND (next_check_at IS NULL OR next_check_at >= started_at)
        AND (completed_at IS NULL OR completed_at >= started_at)
    ),
    CONSTRAINT operator_retry_idempotency_unique UNIQUE (
        actor_issuer, actor_subject, idempotency_key_hash
    )
);

CREATE UNIQUE INDEX operator_retry_one_active_per_operation
    ON operator_retry_commands (operation_type, source_id)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'WAITING');

ALTER TABLE operator_recovery_state
    ADD CONSTRAINT operator_recovery_last_command_fk
    FOREIGN KEY (last_command_id) REFERENCES operator_retry_commands (id);

CREATE INDEX operator_retry_claim_idx
    ON operator_retry_commands (accepted_at, id)
    WHERE status = 'PENDING';

CREATE INDEX operator_retry_waiting_idx
    ON operator_retry_commands (next_check_at, accepted_at, id)
    WHERE status = 'WAITING';

CREATE INDEX operator_retry_expired_lease_idx
    ON operator_retry_commands (lease_until, accepted_at, id)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX operator_retry_operation_history_idx
    ON operator_retry_commands (operation_type, source_id, accepted_at DESC, id DESC);

CREATE TABLE operator_break_glass_uses (
    approval_id uuid PRIMARY KEY REFERENCES operator_break_glass_approvals (id),
    retry_command_id uuid NOT NULL UNIQUE REFERENCES operator_retry_commands (id),
    used_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE operator_retry_attempts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    retry_command_id uuid NOT NULL REFERENCES operator_retry_commands (id),
    lease_token uuid,
    action varchar(32) NOT NULL,
    outcome_code varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT operator_retry_attempt_action_valid CHECK (
        action IN ('CLAIMED', 'TAKEN_OVER', 'WAITING', 'COMPLETED', 'FAILED', 'STALE_REJECTED')
    ),
    CONSTRAINT operator_retry_attempt_outcome_valid CHECK (
        outcome_code ~ '^[A-Z0-9_]{1,64}$'
    )
);

CREATE INDEX operator_retry_attempt_history_idx
    ON operator_retry_attempts (retry_command_id, occurred_at, id);

CREATE TABLE operator_audit_records (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    operation_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    retry_command_id uuid REFERENCES operator_retry_commands (id),
    approval_id uuid REFERENCES operator_break_glass_approvals (id),
    actor_issuer varchar(500) NOT NULL,
    actor_subject varchar(200) NOT NULL,
    actor_client_id varchar(200),
    identity_source varchar(32) NOT NULL,
    action varchar(40) NOT NULL,
    reason varchar(500) NOT NULL,
    outcome_code varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT operator_audit_type_valid CHECK (
        operation_type IN ('PAYMENT', 'OUTBOX', 'DEAD_LETTER')
    ),
    CONSTRAINT operator_audit_identity_source_valid CHECK (
        identity_source IN ('OIDC_JWT', 'TRUSTED_WORKLOAD')
    ),
    CONSTRAINT operator_audit_actor_valid CHECK (
        char_length(actor_issuer) BETWEEN 1 AND 500
        AND actor_issuer !~ '[[:cntrl:]]'
        AND char_length(actor_subject) BETWEEN 1 AND 200
        AND actor_subject !~ '[[:cntrl:]]'
        AND (actor_client_id IS NULL OR (
            char_length(actor_client_id) BETWEEN 1 AND 200
            AND actor_client_id !~ '[[:cntrl:]]'
        ))
    ),
    CONSTRAINT operator_audit_action_valid CHECK (
        action IN (
            'RETRY_ACCEPTED', 'RETRY_COMPLETED', 'RETRY_FAILED',
            'BREAK_GLASS_APPROVED', 'BREAK_GLASS_USED', 'STALE_COMPLETION_REJECTED'
        )
    ),
    CONSTRAINT operator_audit_reason_valid CHECK (
        char_length(reason) BETWEEN 10 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT operator_audit_outcome_valid CHECK (
        outcome_code ~ '^[A-Z0-9_]{1,64}$'
    ),
    CONSTRAINT operator_audit_correlation_valid CHECK (
        correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    )
);

CREATE INDEX operator_audit_operation_time_idx
    ON operator_audit_records (operation_type, source_id, occurred_at, id);

CREATE INDEX operator_audit_command_time_idx
    ON operator_audit_records (retry_command_id, occurred_at, id)
    WHERE retry_command_id IS NOT NULL;

CREATE FUNCTION reject_operator_immutable_record_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'operator recovery evidence is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER operator_approval_no_update_or_delete
BEFORE UPDATE OR DELETE ON operator_break_glass_approvals
FOR EACH ROW EXECUTE FUNCTION reject_operator_immutable_record_mutation();

CREATE TRIGGER operator_break_glass_use_no_update_or_delete
BEFORE UPDATE OR DELETE ON operator_break_glass_uses
FOR EACH ROW EXECUTE FUNCTION reject_operator_immutable_record_mutation();

CREATE TRIGGER operator_retry_attempt_no_update_or_delete
BEFORE UPDATE OR DELETE ON operator_retry_attempts
FOR EACH ROW EXECUTE FUNCTION reject_operator_immutable_record_mutation();

CREATE TRIGGER operator_audit_no_update_or_delete
BEFORE UPDATE OR DELETE ON operator_audit_records
FOR EACH ROW EXECUTE FUNCTION reject_operator_immutable_record_mutation();

CREATE FUNCTION protect_operator_retry_command() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'operator retry commands are durable and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id, NEW.operation_type, NEW.source_id,
        NEW.idempotency_key_hash, NEW.request_hash,
        NEW.attempt_kind, NEW.attempt_number, NEW.approval_id,
        NEW.reason, NEW.actor_issuer, NEW.actor_subject, NEW.actor_client_id,
        NEW.request_correlation_id, NEW.request_traceparent,
        NEW.origin_traceparent, NEW.accepted_at
    ) IS DISTINCT FROM ROW(
        OLD.id, OLD.operation_type, OLD.source_id,
        OLD.idempotency_key_hash, OLD.request_hash,
        OLD.attempt_kind, OLD.attempt_number, OLD.approval_id,
        OLD.reason, OLD.actor_issuer, OLD.actor_subject, OLD.actor_client_id,
        OLD.request_correlation_id, OLD.request_traceparent,
        OLD.origin_traceparent, OLD.accepted_at
    ) THEN
        RAISE EXCEPTION 'operator retry request evidence is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER operator_retry_command_protected
BEFORE UPDATE OR DELETE ON operator_retry_commands
FOR EACH ROW EXECUTE FUNCTION protect_operator_retry_command();

CREATE FUNCTION protect_operator_recovery_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'operator recovery state cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF NEW.operation_type IS DISTINCT FROM OLD.operation_type
        OR NEW.source_id IS DISTINCT FROM OLD.source_id
        OR NEW.automatic_attempt_count < OLD.automatic_attempt_count
        OR NEW.break_glass_attempt_count < OLD.break_glass_attempt_count THEN
        RAISE EXCEPTION 'operator recovery identity and counters cannot be rewritten'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER operator_recovery_state_protected
BEFORE UPDATE OR DELETE ON operator_recovery_state
FOR EACH ROW EXECUTE FUNCTION protect_operator_recovery_state();

ALTER TABLE message_replay_audit
    ADD COLUMN identity_source varchar(32) NOT NULL DEFAULT 'LEGACY_CALLER_ASSERTED';

ALTER TABLE message_replay_audit
    ADD CONSTRAINT message_replay_audit_identity_source_valid CHECK (
        identity_source IN ('LEGACY_CALLER_ASSERTED', 'TRUSTED_WORKLOAD')
    );

INSERT INTO operator_recovery_state (
    operation_type,
    source_id,
    automatic_attempt_count,
    break_glass_attempt_count,
    retry_available_at,
    created_at,
    updated_at
)
SELECT
    'DEAD_LETTER',
    id,
    LEAST(replay_count, 3),
    LEAST(GREATEST(replay_count - 3, 0), 1000),
    replay_available_at,
    dead_lettered_at,
    statement_timestamp()
FROM dead_letter_records
WHERE replay_count > 0
ON CONFLICT (operation_type, source_id) DO NOTHING;

COMMENT ON TABLE operator_retry_commands IS
    'Durable idempotent operator commands; request identity is immutable and execution uses leases.';
COMMENT ON TABLE operator_audit_records IS
    'Append-only authenticated privileged-action evidence; never exposed as a mutable workflow table.';
COMMENT ON COLUMN operator_break_glass_approvals.approval_reference IS
    'Bounded external incident/change reference only; never a ticket body or credential.';
