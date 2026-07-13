CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_subject varchar(200) NOT NULL,
    client_reference varchar(100),
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(32) NOT NULL,
    initial_correlation_id varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT orders_owner_subject_not_blank CHECK (btrim(owner_subject) <> ''),
    CONSTRAINT orders_client_reference_not_blank
        CHECK (client_reference IS NULL OR btrim(client_reference) <> ''),
    CONSTRAINT orders_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT orders_currency_uppercase CHECK (currency = upper(currency)),
    CONSTRAINT orders_currency_mvp CHECK (currency = 'INR'),
    CONSTRAINT orders_status_created CHECK (status = 'CREATED'),
    CONSTRAINT orders_correlation_id_not_blank CHECK (btrim(initial_correlation_id) <> ''),
    CONSTRAINT orders_version_non_negative CHECK (version >= 0),
    CONSTRAINT orders_timestamp_order CHECK (updated_at >= created_at)
);

CREATE INDEX orders_owner_created_idx ON orders (owner_subject, created_at DESC, id DESC);

CREATE TABLE idempotency_records (
    principal_scope varchar(200) NOT NULL,
    operation varchar(100) NOT NULL,
    key_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    state varchar(16) NOT NULL,
    resource_id uuid REFERENCES orders (id),
    response_status integer,
    response_location varchar(300),
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (principal_scope, operation, key_hash),
    CONSTRAINT idempotency_scope_not_blank CHECK (btrim(principal_scope) <> ''),
    CONSTRAINT idempotency_operation_not_blank CHECK (btrim(operation) <> ''),
    CONSTRAINT idempotency_key_hash_sha256 CHECK (octet_length(key_hash) = 32),
    CONSTRAINT idempotency_request_hash_sha256 CHECK (octet_length(request_hash) = 32),
    CONSTRAINT idempotency_state_valid CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_timestamp_order CHECK (updated_at >= created_at),
    CONSTRAINT idempotency_completion_consistent CHECK (
        (state = 'IN_PROGRESS'
            AND resource_id IS NULL
            AND response_status IS NULL
            AND response_location IS NULL
            AND response_body IS NULL
            AND completed_at IS NULL)
        OR
        (state = 'COMPLETED'
            AND resource_id IS NOT NULL
            AND response_status = 201
            AND response_location IS NOT NULL
            AND response_body IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);

COMMENT ON TABLE idempotency_records IS
    'Atomic POST result registry; raw idempotency keys and request payloads are never stored.';
