ALTER TABLE payments DROP CONSTRAINT payments_state_valid;
ALTER TABLE payments ADD CONSTRAINT payments_state_valid CHECK (
    state IN (
        'CREATED', 'AUTHORIZING', 'AUTHORIZED', 'DECLINED',
        'AUTHORIZATION_RETRY_PENDING', 'AUTHORIZATION_UNKNOWN',
        'CAPTURING', 'CAPTURE_CONFIRMED', 'CAPTURE_ACCOUNTED', 'CAPTURE_DECLINED',
        'CAPTURE_RETRY_PENDING', 'CAPTURE_UNKNOWN', 'FAILED'
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
    (state IN ('CAPTURE_CONFIRMED', 'CAPTURE_ACCOUNTED')
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

CREATE TABLE ledger_accounts (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    account_type varchar(16) NOT NULL,
    currency char(3) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    CONSTRAINT ledger_account_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ledger_account_type_valid CHECK (account_type IN ('ASSET', 'LIABILITY')),
    CONSTRAINT ledger_account_currency_mvp CHECK (currency = 'INR')
);

INSERT INTO ledger_accounts (id, code, account_type, currency, active, created_at) VALUES
    ('00000000-0000-4000-8000-000000000001', 'PAYMENT_CLEARING', 'ASSET', 'INR', true,
        statement_timestamp()),
    ('00000000-0000-4000-8000-000000000002', 'MERCHANT_PAYABLE', 'LIABILITY', 'INR', true,
        statement_timestamp());

CREATE TABLE ledger_transactions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    journal_type varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    payment_id uuid NOT NULL REFERENCES payments (id),
    order_id uuid NOT NULL REFERENCES orders (id),
    currency char(3) NOT NULL,
    reversal_of_transaction_id uuid UNIQUE REFERENCES ledger_transactions (id),
    description varchar(200) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    created_by varchar(100) NOT NULL,
    posted_at timestamptz NOT NULL,
    CONSTRAINT ledger_transaction_journal_type_valid CHECK (
        journal_type IN ('PAYMENT_CAPTURE', 'CORRECTION')
    ),
    CONSTRAINT ledger_transaction_currency_mvp CHECK (currency = 'INR'),
    CONSTRAINT ledger_transaction_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT ledger_transaction_correlation_valid CHECK (
        correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT ledger_transaction_actor_valid CHECK (
        created_by ~ '^[A-Za-z0-9._:@-]{1,100}$'
    ),
    CONSTRAINT ledger_transaction_source_shape CHECK (
        (journal_type = 'PAYMENT_CAPTURE'
            AND source_type = 'PAYMENT_CAPTURE'
            AND source_id = payment_id
            AND reversal_of_transaction_id IS NULL)
        OR
        (journal_type = 'CORRECTION'
            AND source_type = 'LEDGER_CORRECTION'
            AND source_id = reversal_of_transaction_id
            AND reversal_of_transaction_id IS NOT NULL)
    ),
    CONSTRAINT ledger_transaction_source_unique UNIQUE (source_type, source_id)
);

CREATE UNIQUE INDEX ledger_one_capture_per_payment_idx
    ON ledger_transactions (payment_id)
    WHERE journal_type = 'PAYMENT_CAPTURE';

CREATE INDEX ledger_transaction_payment_time_idx
    ON ledger_transactions (payment_id, posted_at, id);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    transaction_id uuid NOT NULL REFERENCES ledger_transactions (id),
    account_id uuid NOT NULL REFERENCES ledger_accounts (id),
    side char(1) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ledger_entry_side_valid CHECK (side IN ('D', 'C')),
    CONSTRAINT ledger_entry_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ledger_entry_currency_mvp CHECK (currency = 'INR'),
    CONSTRAINT ledger_entry_account_side_unique UNIQUE (transaction_id, account_id, side)
);

CREATE INDEX ledger_entry_account_time_idx
    ON ledger_entries (account_id, created_at, id);

CREATE FUNCTION enforce_ledger_entry_references() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    account_currency char(3);
    account_active boolean;
    transaction_currency char(3);
BEGIN
    SELECT currency, active INTO account_currency, account_active
    FROM ledger_accounts
    WHERE id = NEW.account_id;

    SELECT currency INTO transaction_currency
    FROM ledger_transactions
    WHERE id = NEW.transaction_id;

    IF account_currency IS NULL OR transaction_currency IS NULL THEN
        RAISE EXCEPTION 'ledger entry references are invalid' USING ERRCODE = '23514';
    END IF;
    IF NOT account_active THEN
        RAISE EXCEPTION 'ledger account is inactive' USING ERRCODE = '23514';
    END IF;
    IF NEW.currency <> account_currency OR NEW.currency <> transaction_currency THEN
        RAISE EXCEPTION 'ledger currencies must match' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_entry_references
BEFORE INSERT OR UPDATE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_entry_references();

CREATE FUNCTION validate_ledger_transaction(target_transaction_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    journal ledger_transactions%ROWTYPE;
    original ledger_transactions%ROWTYPE;
    payment_amount bigint;
    payment_currency char(3);
    payment_order_id uuid;
    payment_state varchar(40);
    entry_count bigint;
    debit_total numeric;
    credit_total numeric;
    clearing_debits bigint;
    payable_credits bigint;
BEGIN
    SELECT * INTO journal
    FROM ledger_transactions
    WHERE id = target_transaction_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT
        count(*),
        COALESCE(sum(amount_minor) FILTER (WHERE side = 'D'), 0),
        COALESCE(sum(amount_minor) FILTER (WHERE side = 'C'), 0)
    INTO entry_count, debit_total, credit_total
    FROM ledger_entries
    WHERE transaction_id = target_transaction_id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'journal transaction requires at least two entries'
            USING ERRCODE = '23514';
    END IF;
    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'journal transaction is unbalanced' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM ledger_entries
        WHERE transaction_id = target_transaction_id
          AND currency <> journal.currency
    ) THEN
        RAISE EXCEPTION 'journal entry currency differs from transaction'
            USING ERRCODE = '23514';
    END IF;

    SELECT amount_minor, currency, order_id, state
    INTO payment_amount, payment_currency, payment_order_id, payment_state
    FROM payments
    WHERE id = journal.payment_id;

    IF payment_amount IS NULL
        OR payment_currency <> journal.currency
        OR payment_order_id <> journal.order_id THEN
        RAISE EXCEPTION 'journal transaction does not match its payment'
            USING ERRCODE = '23514';
    END IF;

    IF journal.journal_type = 'PAYMENT_CAPTURE' THEN
        SELECT
            count(*) FILTER (WHERE a.code = 'PAYMENT_CLEARING' AND e.side = 'D'),
            count(*) FILTER (WHERE a.code = 'MERCHANT_PAYABLE' AND e.side = 'C')
        INTO clearing_debits, payable_credits
        FROM ledger_entries e
        JOIN ledger_accounts a ON a.id = e.account_id
        WHERE e.transaction_id = target_transaction_id;

        IF payment_state <> 'CAPTURE_ACCOUNTED'
            OR entry_count <> 2
            OR debit_total <> payment_amount
            OR credit_total <> payment_amount
            OR clearing_debits <> 1
            OR payable_credits <> 1 THEN
            RAISE EXCEPTION 'payment capture journal is inconsistent'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT * INTO original
        FROM ledger_transactions
        WHERE id = journal.reversal_of_transaction_id;

        IF NOT FOUND
            OR original.journal_type <> 'PAYMENT_CAPTURE'
            OR original.payment_id <> journal.payment_id
            OR original.order_id <> journal.order_id
            OR original.currency <> journal.currency THEN
            RAISE EXCEPTION 'correction does not match its original transaction'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT 1
            FROM ledger_entries original_entry
            WHERE original_entry.transaction_id = original.id
              AND NOT EXISTS (
                  SELECT 1
                  FROM ledger_entries correction_entry
                  WHERE correction_entry.transaction_id = journal.id
                    AND correction_entry.account_id = original_entry.account_id
                    AND correction_entry.amount_minor = original_entry.amount_minor
                    AND correction_entry.currency = original_entry.currency
                    AND correction_entry.side = CASE original_entry.side
                        WHEN 'D' THEN 'C' ELSE 'D' END
              )
        ) OR EXISTS (
            SELECT 1
            FROM ledger_entries correction_entry
            WHERE correction_entry.transaction_id = journal.id
              AND NOT EXISTS (
                  SELECT 1
                  FROM ledger_entries original_entry
                  WHERE original_entry.transaction_id = original.id
                    AND original_entry.account_id = correction_entry.account_id
                    AND original_entry.amount_minor = correction_entry.amount_minor
                    AND original_entry.currency = correction_entry.currency
                    AND original_entry.side = CASE correction_entry.side
                        WHEN 'D' THEN 'C' ELSE 'D' END
              )
        ) THEN
            RAISE EXCEPTION 'correction entries must reverse the original transaction'
                USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION enforce_ledger_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN
        target_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        target_id := OLD.transaction_id;
    ELSE
        target_id := NEW.transaction_id;
    END IF;
    PERFORM validate_ledger_transaction(target_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transaction_balance
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_balance();

CREATE CONSTRAINT TRIGGER ledger_entry_balance
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_balance();

CREATE FUNCTION reject_posted_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'posted ledger records are append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_transaction_no_update_or_delete
BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION reject_posted_ledger_mutation();

CREATE TRIGGER ledger_entry_no_update_or_delete
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_posted_ledger_mutation();

CREATE FUNCTION enforce_ledger_account_immutability() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger accounts cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF OLD.code <> NEW.code
        OR OLD.account_type <> NEW.account_type
        OR OLD.currency <> NEW.currency THEN
        RAISE EXCEPTION 'ledger account identity is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_no_delete
BEFORE DELETE ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_account_immutability();

CREATE TRIGGER ledger_account_identity_immutable
BEFORE UPDATE ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION enforce_ledger_account_immutability();
