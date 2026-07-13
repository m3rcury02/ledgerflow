CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    order_id uuid NOT NULL UNIQUE REFERENCES orders (id),
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    state varchar(40) NOT NULL,
    resume_stage varchar(16),
    payment_method_reference varchar(128),
    authorization_request_id uuid NOT NULL UNIQUE,
    capture_request_id uuid UNIQUE,
    provider_authorization_id varchar(100) UNIQUE,
    provider_capture_id varchar(100) UNIQUE,
    failure_code varchar(64),
    authorization_attempt_count integer NOT NULL DEFAULT 0,
    capture_attempt_count integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payments_currency_mvp CHECK (currency = 'INR'),
    CONSTRAINT payments_state_valid CHECK (
        state IN (
            'CREATED', 'AUTHORIZING', 'AUTHORIZED', 'DECLINED',
            'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
            'CAPTURING', 'CAPTURE_CONFIRMED', 'CAPTURE_DECLINED',
            'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'FAILED'
        )
    ),
    CONSTRAINT payments_resume_stage_valid CHECK (
        resume_stage IS NULL OR resume_stage IN ('AUTHORIZATION', 'CAPTURE')
    ),
    CONSTRAINT payments_attempt_counts_non_negative CHECK (
        authorization_attempt_count >= 0 AND capture_attempt_count >= 0
    ),
    CONSTRAINT payments_version_non_negative CHECK (version >= 0),
    CONSTRAINT payments_timestamp_order CHECK (updated_at >= created_at),
    CONSTRAINT payments_state_shape CHECK (
        (state = 'CREATED'
            AND payment_method_reference IS NOT NULL
            AND provider_authorization_id IS NULL
            AND capture_request_id IS NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NULL
            AND resume_stage IS NULL)
        OR
        (state = 'AUTHORIZING'
            AND payment_method_reference IS NOT NULL
            AND provider_authorization_id IS NULL
            AND capture_request_id IS NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NULL
            AND resume_stage = 'AUTHORIZATION')
        OR
        (state IN ('AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN')
            AND payment_method_reference IS NOT NULL
            AND provider_authorization_id IS NULL
            AND capture_request_id IS NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NOT NULL
            AND resume_stage = 'AUTHORIZATION')
        OR
        (state = 'DECLINED'
            AND payment_method_reference IS NULL
            AND provider_authorization_id IS NULL
            AND capture_request_id IS NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NOT NULL
            AND resume_stage IS NULL)
        OR
        (state = 'AUTHORIZED'
            AND payment_method_reference IS NULL
            AND provider_authorization_id IS NOT NULL
            AND capture_request_id IS NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NULL
            AND resume_stage IS NULL)
        OR
        (state = 'CAPTURING'
            AND payment_method_reference IS NULL
            AND provider_authorization_id IS NOT NULL
            AND capture_request_id IS NOT NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NULL
            AND resume_stage = 'CAPTURE')
        OR
        (state IN ('CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'CAPTURE_DECLINED')
            AND payment_method_reference IS NULL
            AND provider_authorization_id IS NOT NULL
            AND capture_request_id IS NOT NULL
            AND provider_capture_id IS NULL
            AND failure_code IS NOT NULL
            AND (
                (state = 'CAPTURE_DECLINED' AND resume_stage IS NULL)
                OR (state <> 'CAPTURE_DECLINED' AND resume_stage = 'CAPTURE')
            ))
        OR
        (state = 'CAPTURE_CONFIRMED'
            AND payment_method_reference IS NULL
            AND provider_authorization_id IS NOT NULL
            AND capture_request_id IS NOT NULL
            AND provider_capture_id IS NOT NULL
            AND failure_code IS NULL
            AND resume_stage IS NULL)
        OR
        (state = 'FAILED'
            AND payment_method_reference IS NULL
            AND failure_code IS NOT NULL
            AND resume_stage IS NULL)
    )
);

CREATE INDEX payments_state_updated_idx ON payments (state, updated_at, id);

CREATE TABLE payment_attempt_history (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    payment_id uuid NOT NULL REFERENCES payments (id),
    stage varchar(16) NOT NULL,
    activity varchar(16) NOT NULL,
    attempt_number integer NOT NULL,
    outcome varchar(32) NOT NULL,
    provider_request_id uuid NOT NULL,
    provider_reference varchar(100),
    failure_code varchar(64),
    correlation_id varchar(64) NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT payment_history_stage_valid CHECK (stage IN ('AUTHORIZATION', 'CAPTURE')),
    CONSTRAINT payment_history_activity_valid CHECK (activity IN ('CALL', 'LOOKUP')),
    CONSTRAINT payment_history_attempt_positive CHECK (attempt_number >= 1),
    CONSTRAINT payment_history_outcome_valid CHECK (
        outcome IN (
            'STARTED', 'SUCCEEDED', 'DECLINED', 'TEMPORARY_FAILURE', 'TIMEOUT',
            'UNKNOWN', 'NOT_FOUND', 'INVALID_RESPONSE'
        )
    ),
    CONSTRAINT payment_history_correlation_not_blank CHECK (btrim(correlation_id) <> ''),
    CONSTRAINT payment_history_result_shape CHECK (
        (outcome = 'SUCCEEDED' AND provider_reference IS NOT NULL AND failure_code IS NULL)
        OR
        (outcome = 'STARTED' AND provider_reference IS NULL AND failure_code IS NULL)
        OR
        (outcome NOT IN ('STARTED', 'SUCCEEDED')
            AND provider_reference IS NULL
            AND failure_code IS NOT NULL)
    )
);

CREATE INDEX payment_history_payment_time_idx
    ON payment_attempt_history (payment_id, recorded_at, id);

CREATE FUNCTION enforce_payment_money_matches_order() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM orders o
        WHERE o.id = NEW.order_id
          AND o.amount_minor = NEW.amount_minor
          AND o.currency = NEW.currency
    ) THEN
        RAISE EXCEPTION 'payment money must match its order' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payments_money_matches_order
BEFORE INSERT OR UPDATE OF order_id, amount_minor, currency ON payments
FOR EACH ROW EXECUTE FUNCTION enforce_payment_money_matches_order();

CREATE FUNCTION reject_payment_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment attempt history is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER payment_history_no_update_or_delete
BEFORE UPDATE OR DELETE ON payment_attempt_history
FOR EACH ROW EXECUTE FUNCTION reject_payment_history_mutation();
