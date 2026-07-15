CREATE TABLE terminal_dlt_records (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    consumer_name varchar(100) NOT NULL,
    dlt_topic varchar(249) NOT NULL,
    dlt_partition integer NOT NULL,
    dlt_offset bigint NOT NULL,
    key_hash bytea NOT NULL,
    key_size integer NOT NULL,
    payload_hash bytea NOT NULL,
    payload_size integer NOT NULL,
    safe_headers jsonb NOT NULL DEFAULT '{}'::jsonb,
    failure_code varchar(64) NOT NULL,
    failure_summary varchar(500) NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT terminal_dlt_consumer_name_valid CHECK (
        consumer_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
    ),
    CONSTRAINT terminal_dlt_topic_valid CHECK (
        dlt_topic ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$'
    ),
    CONSTRAINT terminal_dlt_partition_non_negative CHECK (dlt_partition >= 0),
    CONSTRAINT terminal_dlt_offset_non_negative CHECK (dlt_offset >= 0),
    CONSTRAINT terminal_dlt_key_hash_sha256 CHECK (octet_length(key_hash) = 32),
    CONSTRAINT terminal_dlt_key_size_non_negative CHECK (key_size >= 0),
    CONSTRAINT terminal_dlt_payload_hash_sha256 CHECK (octet_length(payload_hash) = 32),
    CONSTRAINT terminal_dlt_payload_size_non_negative CHECK (payload_size >= 0),
    CONSTRAINT terminal_dlt_safe_headers_object CHECK (jsonb_typeof(safe_headers) = 'object'),
    CONSTRAINT terminal_dlt_failure_valid CHECK (
        failure_code ~ '^[A-Z0-9_]{1,64}$'
        AND btrim(failure_summary) <> ''
        AND failure_summary !~ '[[:cntrl:]]'
    ),
    CONSTRAINT terminal_dlt_coordinate_unique UNIQUE (
        consumer_name,
        dlt_topic,
        dlt_partition,
        dlt_offset
    )
);

CREATE INDEX terminal_dlt_observed_idx
    ON terminal_dlt_records (observed_at, id);

CREATE FUNCTION reject_terminal_dlt_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'terminal DLT evidence is immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER terminal_dlt_evidence_no_update_or_delete
BEFORE UPDATE OR DELETE ON terminal_dlt_records
FOR EACH ROW EXECUTE FUNCTION reject_terminal_dlt_evidence_mutation();

COMMENT ON TABLE terminal_dlt_records IS
    'Sanitized terminal evidence keyed by actual DLT coordinates; raw key and payload are excluded.';
