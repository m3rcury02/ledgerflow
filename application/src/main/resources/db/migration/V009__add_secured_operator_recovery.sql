CREATE TABLE operator_retry_commands (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    idempotency_key_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    operation_id uuid NOT NULL,
    operation_type varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    reason varchar(500) NOT NULL,
    break_glass boolean NOT NULL DEFAULT false,
    approval_reference varchar(100),
    actor_issuer varchar(200) NOT NULL,
    actor_subject varchar(200) NOT NULL,
    actor_client_id varchar(200),
    lease_owner varchar(100),
    lease_until timestamptz,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    resolved_at timestamptz,
    CONSTRAINT retry_command_idempotency_hash_sha256 CHECK (octet_length(idempotency_key_hash) = 32),
    CONSTRAINT retry_command_request_hash_sha256 CHECK (octet_length(request_hash) = 32),
    CONSTRAINT retry_command_op_type_valid CHECK (operation_type IN ('PAYMENT', 'OUTBOX', 'DEAD_LETTER')),
    CONSTRAINT retry_command_status_valid CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT retry_command_reason_valid CHECK (
        char_length(reason) BETWEEN 10 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT retry_command_actor_valid CHECK (
        btrim(actor_issuer) <> '' AND btrim(actor_subject) <> ''
    ),
    CONSTRAINT retry_command_lease_shape CHECK (
        (status = 'PENDING' AND lease_owner IS NULL AND lease_until IS NULL)
        OR
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status IN ('COMPLETED', 'FAILED') AND lease_owner IS NULL AND lease_until IS NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT retry_command_idempotency_unique UNIQUE (idempotency_key_hash)
);

CREATE INDEX operator_retry_commands_pending_idx 
    ON operator_retry_commands (created_at, id) 
    WHERE status = 'PENDING';

CREATE INDEX operator_retry_commands_expired_idx 
    ON operator_retry_commands (lease_until, id) 
    WHERE status = 'IN_PROGRESS';

CREATE VIEW failed_operation_projections AS
SELECT
    id AS operation_id,
    'PAYMENT' AS operation_type,
    state AS status,
    updated_at AS failed_at,
    failure_code,
    null AS summary
FROM payments
WHERE state IN ('FAILED', 'AUTHORIZATION_UNKNOWN', 'CAPTURE_UNKNOWN')
UNION ALL
SELECT
    event_id AS operation_id,
    'OUTBOX' AS operation_type,
    status AS status,
    last_failed_at AS failed_at,
    last_failure_code AS failure_code,
    null AS summary
FROM outbox_events
WHERE status = 'FAILED'
UNION ALL
SELECT
    id AS operation_id,
    'DEAD_LETTER' AS operation_type,
    status AS status,
    dead_lettered_at AS failed_at,
    failure_code,
    failure_summary AS summary
FROM dead_letter_records
WHERE status IN ('OPEN', 'REPLAYING', 'REPLAYED', 'RESOLVED');

ALTER TABLE message_replay_audit 
ADD COLUMN identity_source varchar(32) NOT NULL DEFAULT 'LEGACY_CALLER_ASSERTED',
ADD COLUMN actor_issuer varchar(200),
ADD COLUMN actor_subject varchar(200),
ADD COLUMN actor_client_id varchar(200),
ADD COLUMN break_glass boolean NOT NULL DEFAULT false,
ADD COLUMN approval_reference varchar(100);

ALTER TABLE message_replay_audit
ADD CONSTRAINT replay_audit_identity_valid CHECK (
    identity_source IN ('LEGACY_CALLER_ASSERTED', 'OIDC_JWT')
);

ALTER TABLE message_replay_audit
ADD CONSTRAINT replay_audit_oidc_actor_valid CHECK (
    (identity_source = 'OIDC_JWT' AND actor_issuer IS NOT NULL AND actor_subject IS NOT NULL)
    OR
    (identity_source = 'LEGACY_CALLER_ASSERTED' AND actor_issuer IS NULL AND actor_subject IS NULL AND actor_client_id IS NULL)
);

-- update trigger to allow adding columns without failing the append-only check.
-- the trigger previously checked NEW.id != OLD.id, but DDL isn't caught by the DML trigger.
