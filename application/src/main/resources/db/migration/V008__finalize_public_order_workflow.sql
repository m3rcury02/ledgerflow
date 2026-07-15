ALTER TABLE orders DROP CONSTRAINT orders_status_created;
ALTER TABLE orders ADD CONSTRAINT orders_status_valid CHECK (
    status IN (
        'CREATED', 'PAYMENT_PROCESSING', 'COMPLETED', 'PAYMENT_DECLINED',
        'PAYMENT_RETRY_PENDING', 'FAILED'
    )
);

ALTER TABLE idempotency_records DROP CONSTRAINT idempotency_completion_consistent;
ALTER TABLE idempotency_records ADD CONSTRAINT idempotency_completion_consistent CHECK (
    (state = 'IN_PROGRESS'
        AND response_status IS NULL
        AND response_location IS NULL
        AND response_body IS NULL
        AND completed_at IS NULL)
    OR
    (state = 'COMPLETED'
        AND resource_id IS NOT NULL
        AND response_status IN (201, 202, 502)
        AND response_location IS NOT NULL
        AND response_body IS NOT NULL
        AND completed_at IS NOT NULL)
);

ALTER TABLE payments DROP CONSTRAINT payments_state_valid;
ALTER TABLE payments ADD CONSTRAINT payments_state_valid CHECK (
    state IN (
        'CREATED', 'AUTHORIZING', 'AUTHORIZED', 'DECLINED',
        'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
        'CAPTURING', 'CAPTURE_CONFIRMED', 'CAPTURE_ACCOUNTED', 'CAPTURED',
        'CAPTURE_DECLINED', 'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'FAILED'
    )
);

ALTER TABLE payments DROP CONSTRAINT payments_state_shape;
ALTER TABLE payments ADD CONSTRAINT payments_state_shape CHECK (
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
    (state IN ('CAPTURE_CONFIRMED', 'CAPTURE_ACCOUNTED', 'CAPTURED')
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
);

CREATE FUNCTION validate_terminal_order_workflow(target_order_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    target_order orders%ROWTYPE;
    target_payment payments%ROWTYPE;
    capture_journal_id uuid;
BEGIN
    SELECT * INTO target_order FROM orders WHERE id = target_order_id;
    IF NOT FOUND OR target_order.status IN ('CREATED', 'PAYMENT_PROCESSING') THEN
        RETURN;
    END IF;

    SELECT * INTO target_payment FROM payments WHERE order_id = target_order_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'terminal order requires one payment' USING ERRCODE = '23514';
    END IF;

    IF target_order.status = 'COMPLETED' THEN
        IF target_payment.state <> 'CAPTURED' THEN
            RAISE EXCEPTION 'completed order requires captured payment' USING ERRCODE = '23514';
        END IF;

        SELECT id INTO capture_journal_id
        FROM ledger_transactions
        WHERE payment_id = target_payment.id AND journal_type = 'PAYMENT_CAPTURE';
        IF capture_journal_id IS NULL THEN
            RAISE EXCEPTION 'completed order requires capture journal' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1
            FROM outbox_events
            WHERE deduplication_key = 'payment-captured:' || target_payment.id::text
              AND aggregate_id = target_payment.id
              AND event_type = 'com.ledgerflow.payment.captured'
              AND payload #>> '{data,ledgerTransactionId}' = capture_journal_id::text
        ) THEN
            RAISE EXCEPTION 'completed order requires payment-captured outbox event'
                USING ERRCODE = '23514';
        END IF;
    ELSIF target_order.status = 'PAYMENT_DECLINED' THEN
        IF target_payment.state NOT IN ('DECLINED', 'CAPTURE_DECLINED') THEN
            RAISE EXCEPTION 'declined order requires declined payment' USING ERRCODE = '23514';
        END IF;
    ELSIF target_order.status = 'PAYMENT_RETRY_PENDING' THEN
        IF target_payment.state NOT IN (
            'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
            'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN'
        ) THEN
            RAISE EXCEPTION 'retry-pending order requires recoverable payment'
                USING ERRCODE = '23514';
        END IF;
    ELSIF target_order.status = 'FAILED' AND target_payment.state <> 'FAILED' THEN
        RAISE EXCEPTION 'failed order requires failed payment' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION enforce_terminal_order_workflow() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target_order_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'orders' THEN
        target_order_id := NEW.id;
    ELSE
        target_order_id := NEW.order_id;
    END IF;
    PERFORM validate_terminal_order_workflow(target_order_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER order_terminal_workflow_consistent
AFTER INSERT OR UPDATE OF status ON orders
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_terminal_order_workflow();

CREATE CONSTRAINT TRIGGER payment_terminal_workflow_consistent
AFTER UPDATE OF state ON payments
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (NEW.state = 'CAPTURED')
EXECUTE FUNCTION enforce_terminal_order_workflow();

COMMENT ON FUNCTION validate_terminal_order_workflow(uuid) IS
    'Deferred final-state invariant; provider and Kafka I/O are never part of this transaction.';
