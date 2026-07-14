CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    deduplication_key varchar(200) NOT NULL UNIQUE,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    schema_version smallint NOT NULL,
    topic varchar(249) NOT NULL,
    event_key varchar(200) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash bytea NOT NULL,
    correlation_id varchar(64) NOT NULL,
    causation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    traceparent varchar(55),
    tracestate varchar(512),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    cycle_attempt_count integer NOT NULL DEFAULT 0,
    total_attempt_count bigint NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    lease_owner varchar(100),
    lease_until timestamptz,
    last_failure_code varchar(64),
    last_failed_at timestamptz,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT outbox_deduplication_key_valid CHECK (
        deduplication_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$'
    ),
    CONSTRAINT outbox_aggregate_type_valid CHECK (
        aggregate_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'
    ),
    CONSTRAINT outbox_event_type_valid CHECK (
        event_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'
    ),
    CONSTRAINT outbox_schema_version_positive CHECK (schema_version >= 1),
    CONSTRAINT outbox_topic_valid CHECK (
        topic ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$'
    ),
    CONSTRAINT outbox_event_key_valid CHECK (
        event_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$'
    ),
    CONSTRAINT outbox_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_payload_hash_sha256 CHECK (octet_length(payload_hash) = 32),
    CONSTRAINT outbox_correlation_id_valid CHECK (
        correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT outbox_traceparent_valid CHECK (
        traceparent IS NULL
        OR (
            traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
            AND substring(traceparent FROM 4 FOR 32) <> repeat('0', 32)
            AND substring(traceparent FROM 37 FOR 16) <> repeat('0', 16)
        )
    ),
    CONSTRAINT outbox_tracestate_valid CHECK (
        tracestate IS NULL
        OR (
            char_length(tracestate) BETWEEN 1 AND 512
            AND tracestate !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT outbox_status_valid CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT outbox_attempt_counts_valid CHECK (
        cycle_attempt_count >= 0
        AND total_attempt_count >= 0
        AND total_attempt_count >= cycle_attempt_count
    ),
    CONSTRAINT outbox_lease_owner_valid CHECK (
        lease_owner IS NULL OR lease_owner ~ '^[A-Za-z0-9._:@-]{1,100}$'
    ),
    CONSTRAINT outbox_failure_pair_consistent CHECK (
        (last_failure_code IS NULL AND last_failed_at IS NULL)
        OR
        (last_failure_code ~ '^[A-Z0-9_]{1,64}$' AND last_failed_at IS NOT NULL)
    ),
    CONSTRAINT outbox_delivery_state_shape CHECK (
        (status = 'PENDING'
            AND lease_owner IS NULL
            AND lease_until IS NULL
            AND published_at IS NULL)
        OR
        (status = 'IN_FLIGHT'
            AND lease_owner IS NOT NULL
            AND lease_until IS NOT NULL
            AND published_at IS NULL
            AND last_failure_code IS NULL
            AND last_failed_at IS NULL
            AND cycle_attempt_count >= 1
            AND total_attempt_count >= 1)
        OR
        (status = 'PUBLISHED'
            AND lease_owner IS NULL
            AND lease_until IS NULL
            AND last_failure_code IS NULL
            AND last_failed_at IS NULL
            AND published_at IS NOT NULL
            AND cycle_attempt_count >= 1
            AND total_attempt_count >= 1)
        OR
        (status = 'FAILED'
            AND lease_owner IS NULL
            AND lease_until IS NULL
            AND last_failure_code IS NOT NULL
            AND last_failed_at IS NOT NULL
            AND published_at IS NULL
            AND cycle_attempt_count >= 1
            AND total_attempt_count >= 1)
    ),
    CONSTRAINT outbox_timestamp_order CHECK (
        available_at >= created_at
        AND (lease_until IS NULL OR lease_until > available_at)
        AND (last_failed_at IS NULL OR last_failed_at >= created_at)
        AND (published_at IS NULL OR published_at >= created_at)
    )
);

CREATE INDEX outbox_due_idx
    ON outbox_events (available_at, created_at, event_id)
    WHERE status = 'PENDING';

CREATE INDEX outbox_expired_lease_idx
    ON outbox_events (lease_until, event_id)
    WHERE status = 'IN_FLIGHT';

CREATE INDEX outbox_aggregate_idx
    ON outbox_events (aggregate_type, aggregate_id, occurred_at, event_id);

CREATE FUNCTION reject_outbox_event_identity_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox events are durable and cannot be deleted' USING ERRCODE = '55000';
    END IF;

    IF ROW(
        NEW.event_id,
        NEW.deduplication_key,
        NEW.aggregate_type,
        NEW.aggregate_id,
        NEW.event_type,
        NEW.schema_version,
        NEW.topic,
        NEW.event_key,
        NEW.payload,
        NEW.payload_hash,
        NEW.correlation_id,
        NEW.causation_id,
        NEW.occurred_at,
        NEW.traceparent,
        NEW.tracestate,
        NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.event_id,
        OLD.deduplication_key,
        OLD.aggregate_type,
        OLD.aggregate_id,
        OLD.event_type,
        OLD.schema_version,
        OLD.topic,
        OLD.event_key,
        OLD.payload,
        OLD.payload_hash,
        OLD.correlation_id,
        OLD.causation_id,
        OLD.occurred_at,
        OLD.traceparent,
        OLD.tracestate,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'outbox event identity and body are immutable' USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_event_identity_immutable
BEFORE UPDATE OR DELETE ON outbox_events
FOR EACH ROW EXECUTE FUNCTION reject_outbox_event_identity_mutation();

COMMENT ON COLUMN outbox_events.payload IS
    'Canonical versioned event envelope stored as JSON; never delivery or exception metadata.';
COMMENT ON COLUMN outbox_events.payload_hash IS
    'SHA-256 of the application canonical UTF-8 event-envelope bytes.';
