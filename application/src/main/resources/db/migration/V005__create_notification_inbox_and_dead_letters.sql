CREATE TABLE notification_inbox (
    event_id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    schema_version smallint NOT NULL,
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL,
    offset_value bigint NOT NULL,
    payload_hash bytea NOT NULL,
    received_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    processed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT notification_inbox_event_type_valid CHECK (
        event_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'
    ),
    CONSTRAINT notification_inbox_schema_version_positive CHECK (schema_version >= 1),
    CONSTRAINT notification_inbox_topic_valid CHECK (
        topic ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$'
    ),
    CONSTRAINT notification_inbox_partition_non_negative CHECK (partition_id >= 0),
    CONSTRAINT notification_inbox_offset_non_negative CHECK (offset_value >= 0),
    CONSTRAINT notification_inbox_payload_hash_sha256 CHECK (octet_length(payload_hash) = 32),
    CONSTRAINT notification_inbox_timestamp_order CHECK (processed_at >= received_at),
    CONSTRAINT notification_inbox_coordinate_unique UNIQUE (topic, partition_id, offset_value)
);

CREATE INDEX notification_inbox_processed_idx
    ON notification_inbox (processed_at, event_id);

CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid NOT NULL UNIQUE REFERENCES notification_inbox (event_id),
    order_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    type varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    business_correlation_id varchar(64) NOT NULL,
    processing_correlation_id varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT notification_type_valid CHECK (type = 'PAYMENT_CAPTURED'),
    CONSTRAINT notification_status_valid CHECK (status = 'CREATED'),
    CONSTRAINT notification_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT notification_currency_mvp CHECK (currency = 'INR'),
    CONSTRAINT notification_business_correlation_valid CHECK (
        business_correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT notification_processing_correlation_valid CHECK (
        processing_correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    )
);

CREATE INDEX notification_order_created_idx
    ON notifications (order_id, created_at, id);

CREATE INDEX notification_payment_created_idx
    ON notifications (payment_id, created_at, id);

CREATE TABLE dead_letter_records (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid,
    consumer_name varchar(100) NOT NULL,
    original_topic varchar(249) NOT NULL,
    original_partition integer NOT NULL,
    original_offset bigint NOT NULL,
    event_key varchar(200),
    validated_payload jsonb,
    payload_hash bytea NOT NULL,
    payload_size integer NOT NULL,
    safe_headers jsonb NOT NULL DEFAULT '{}'::jsonb,
    failure_code varchar(64) NOT NULL,
    failure_summary varchar(500) NOT NULL,
    attempt_count integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    replayable boolean NOT NULL DEFAULT false,
    replay_count integer NOT NULL DEFAULT 0,
    replay_available_at timestamptz,
    replay_lease_owner varchar(100),
    replay_lease_until timestamptz,
    last_replay_failure_code varchar(64),
    last_replay_failed_at timestamptz,
    dead_lettered_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    replayed_at timestamptz,
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT dead_letter_consumer_name_valid CHECK (
        consumer_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
    ),
    CONSTRAINT dead_letter_original_topic_valid CHECK (
        original_topic ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$'
    ),
    CONSTRAINT dead_letter_partition_non_negative CHECK (original_partition >= 0),
    CONSTRAINT dead_letter_offset_non_negative CHECK (original_offset >= 0),
    CONSTRAINT dead_letter_event_key_valid CHECK (
        event_key IS NULL OR event_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$'
    ),
    CONSTRAINT dead_letter_validated_payload_object CHECK (
        validated_payload IS NULL OR jsonb_typeof(validated_payload) = 'object'
    ),
    CONSTRAINT dead_letter_payload_hash_sha256 CHECK (octet_length(payload_hash) = 32),
    CONSTRAINT dead_letter_payload_size_bounded CHECK (
        payload_size BETWEEN 0 AND 1048576
    ),
    CONSTRAINT dead_letter_safe_headers_object CHECK (jsonb_typeof(safe_headers) = 'object'),
    CONSTRAINT dead_letter_failure_valid CHECK (
        failure_code ~ '^[A-Z0-9_]{1,64}$'
        AND btrim(failure_summary) <> ''
        AND failure_summary !~ '[[:cntrl:]]'
    ),
    CONSTRAINT dead_letter_attempt_count_positive CHECK (attempt_count >= 1),
    CONSTRAINT dead_letter_status_valid CHECK (
        status IN ('OPEN', 'REPLAYING', 'REPLAYED', 'RESOLVED')
    ),
    CONSTRAINT dead_letter_replay_count_non_negative CHECK (replay_count >= 0),
    CONSTRAINT dead_letter_version_non_negative CHECK (version >= 0),
    CONSTRAINT dead_letter_replay_lease_owner_valid CHECK (
        replay_lease_owner IS NULL
        OR replay_lease_owner ~ '^[A-Za-z0-9._:@-]{1,100}$'
    ),
    CONSTRAINT dead_letter_replay_failure_pair_consistent CHECK (
        (last_replay_failure_code IS NULL AND last_replay_failed_at IS NULL)
        OR
        (
            last_replay_failure_code ~ '^[A-Z0-9_]{1,64}$'
            AND last_replay_failed_at IS NOT NULL
        )
    ),
    CONSTRAINT dead_letter_replay_eligibility_shape CHECK (
        (replayable
            AND event_id IS NOT NULL
            AND event_key IS NOT NULL
            AND validated_payload IS NOT NULL)
        OR
        (NOT replayable
            AND status IN ('OPEN', 'RESOLVED')
            AND replay_count = 0
            AND replay_available_at IS NULL
            AND replay_lease_owner IS NULL
            AND replay_lease_until IS NULL
            AND last_replay_failure_code IS NULL
            AND last_replay_failed_at IS NULL
            AND replayed_at IS NULL)
    ),
    CONSTRAINT dead_letter_status_shape CHECK (
        (status = 'OPEN'
            AND replay_lease_owner IS NULL
            AND replay_lease_until IS NULL
            AND replayed_at IS NULL
            AND resolved_at IS NULL)
        OR
        (status = 'REPLAYING'
            AND replayable
            AND replay_available_at IS NOT NULL
            AND replay_lease_owner IS NOT NULL
            AND replay_lease_until IS NOT NULL
            AND last_replay_failure_code IS NULL
            AND last_replay_failed_at IS NULL
            AND replayed_at IS NULL
            AND resolved_at IS NULL
            AND replay_count >= 1)
        OR
        (status = 'REPLAYED'
            AND replayable
            AND replay_lease_owner IS NULL
            AND replay_lease_until IS NULL
            AND last_replay_failure_code IS NULL
            AND last_replay_failed_at IS NULL
            AND replayed_at IS NOT NULL
            AND resolved_at IS NULL
            AND replay_count >= 1)
        OR
        (status = 'RESOLVED'
            AND replay_lease_owner IS NULL
            AND replay_lease_until IS NULL
            AND last_replay_failure_code IS NULL
            AND last_replay_failed_at IS NULL
            AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT dead_letter_timestamp_order CHECK (
        (replay_available_at IS NULL OR replay_available_at >= dead_lettered_at)
        AND (replay_lease_until IS NULL OR replay_lease_until > replay_available_at)
        AND (last_replay_failed_at IS NULL OR last_replay_failed_at >= dead_lettered_at)
        AND (replayed_at IS NULL OR replayed_at >= dead_lettered_at)
        AND (resolved_at IS NULL OR resolved_at >= COALESCE(replayed_at, dead_lettered_at))
    ),
    CONSTRAINT dead_letter_original_coordinate_unique UNIQUE (
        consumer_name,
        original_topic,
        original_partition,
        original_offset
    )
);

CREATE INDEX dead_letter_open_replay_idx
    ON dead_letter_records (replay_available_at, dead_lettered_at, id)
    WHERE status = 'OPEN' AND replayable;

CREATE INDEX dead_letter_expired_replay_lease_idx
    ON dead_letter_records (replay_lease_until, id)
    WHERE status = 'REPLAYING';

CREATE INDEX dead_letter_event_idx
    ON dead_letter_records (event_id, dead_lettered_at, id)
    WHERE event_id IS NOT NULL;

CREATE FUNCTION reject_dead_letter_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'dead-letter records are durable and cannot be deleted' USING ERRCODE = '55000';
    END IF;

    IF ROW(
        NEW.id,
        NEW.event_id,
        NEW.consumer_name,
        NEW.original_topic,
        NEW.original_partition,
        NEW.original_offset,
        NEW.event_key,
        NEW.validated_payload,
        NEW.payload_hash,
        NEW.payload_size,
        NEW.safe_headers,
        NEW.failure_code,
        NEW.failure_summary,
        NEW.attempt_count,
        NEW.replayable,
        NEW.dead_lettered_at
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.event_id,
        OLD.consumer_name,
        OLD.original_topic,
        OLD.original_partition,
        OLD.original_offset,
        OLD.event_key,
        OLD.validated_payload,
        OLD.payload_hash,
        OLD.payload_size,
        OLD.safe_headers,
        OLD.failure_code,
        OLD.failure_summary,
        OLD.attempt_count,
        OLD.replayable,
        OLD.dead_lettered_at
    ) THEN
        RAISE EXCEPTION 'dead-letter source evidence is immutable' USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER dead_letter_evidence_immutable
BEFORE UPDATE OR DELETE ON dead_letter_records
FOR EACH ROW EXECUTE FUNCTION reject_dead_letter_evidence_mutation();

CREATE TABLE message_replay_audit (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    replay_request_id uuid NOT NULL,
    dead_letter_record_id uuid NOT NULL REFERENCES dead_letter_records (id),
    actor varchar(200) NOT NULL,
    reason varchar(500) NOT NULL,
    action varchar(16) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    failure_code varchar(64),
    failure_summary varchar(500),
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT replay_audit_actor_valid CHECK (
        actor ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'
    ),
    CONSTRAINT replay_audit_reason_valid CHECK (
        char_length(reason) BETWEEN 10 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT replay_audit_action_valid CHECK (
        action IN ('REQUESTED', 'STARTED', 'PUBLISHED', 'FAILED', 'RESOLVED')
    ),
    CONSTRAINT replay_audit_correlation_valid CHECK (
        correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT replay_audit_failure_shape CHECK (
        (action = 'FAILED'
            AND failure_code ~ '^[A-Z0-9_]{1,64}$'
            AND failure_summary IS NOT NULL
            AND btrim(failure_summary) <> ''
            AND failure_summary !~ '[[:cntrl:]]')
        OR
        (action <> 'FAILED' AND failure_code IS NULL AND failure_summary IS NULL)
    )
);

CREATE INDEX replay_audit_record_time_idx
    ON message_replay_audit (dead_letter_record_id, occurred_at, id);

CREATE INDEX replay_audit_request_time_idx
    ON message_replay_audit (replay_request_id, occurred_at, id);

CREATE FUNCTION reject_message_replay_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'message replay audit is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER message_replay_audit_no_update_or_delete
BEFORE UPDATE OR DELETE ON message_replay_audit
FOR EACH ROW EXECUTE FUNCTION reject_message_replay_audit_mutation();

COMMENT ON COLUMN dead_letter_records.validated_payload IS
    'Allowlisted validated event JSON only; malformed raw message bytes are never stored.';
