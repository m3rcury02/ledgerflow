ALTER TABLE notifications
    ADD COLUMN effect_type varchar(64),
    ADD COLUMN effect_identity_version smallint,
    ADD COLUMN effect_key uuid,
    ADD COLUMN source_causation_id uuid,
    ADD COLUMN source_occurred_at timestamptz;

ALTER TABLE notification_inbox
    ADD COLUMN processing_outcome varchar(32);

UPDATE notifications n
SET effect_type = 'PAYMENT_CAPTURED_NOTIFICATION',
    effect_identity_version = 1,
    effect_key = l.id,
    source_causation_id = o.causation_id,
    source_occurred_at = o.occurred_at
FROM outbox_events o
JOIN ledger_transactions l
  ON l.id = ((o.payload #>> '{data,ledgerTransactionId}')::uuid)
WHERE o.event_id = n.event_id
  AND o.event_type = 'com.ledgerflow.payment.captured'
  AND o.schema_version = 1
  AND l.journal_type = 'PAYMENT_CAPTURE'
  AND l.payment_id = n.payment_id
  AND l.order_id = n.order_id
  AND (o.payload #>> '{data,orderId}')::uuid = n.order_id
  AND (o.payload #>> '{data,paymentId}')::uuid = n.payment_id
  AND (o.payload #>> '{data,amountMinor}')::bigint = n.amount_minor
  AND o.payload #>> '{data,currency}' = n.currency
  AND (o.payload #>> '{data,capturedAt}')::timestamptz = o.occurred_at;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM notifications
        WHERE effect_type IS NULL
           OR effect_identity_version IS NULL
           OR effect_key IS NULL
           OR source_causation_id IS NULL
           OR source_occurred_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'notification semantic identity backfill could not map every existing row'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM notifications
        GROUP BY effect_type, effect_identity_version, effect_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'notification semantic identity backfill found duplicate business effects'
            USING ERRCODE = '23505';
    END IF;
END;
$$;

UPDATE notification_inbox i
SET processing_outcome = 'APPLIED'
WHERE EXISTS (SELECT 1 FROM notifications n WHERE n.event_id = i.event_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM notification_inbox WHERE processing_outcome IS NULL) THEN
        RAISE EXCEPTION
            'notification inbox outcome backfill found an event without a notification effect'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

ALTER TABLE notifications
    ALTER COLUMN effect_type SET NOT NULL,
    ALTER COLUMN effect_identity_version SET NOT NULL,
    ALTER COLUMN effect_key SET NOT NULL,
    ALTER COLUMN source_causation_id SET NOT NULL,
    ALTER COLUMN source_occurred_at SET NOT NULL,
    ADD CONSTRAINT notification_effect_type_valid CHECK (
        effect_type = 'PAYMENT_CAPTURED_NOTIFICATION'
    ),
    ADD CONSTRAINT notification_effect_version_valid CHECK (
        effect_identity_version = 1
    ),
    ADD CONSTRAINT notification_semantic_effect_unique UNIQUE (
        effect_type,
        effect_identity_version,
        effect_key
    );

ALTER TABLE notification_inbox
    ALTER COLUMN processing_outcome SET NOT NULL,
    ADD CONSTRAINT notification_inbox_processing_outcome_valid CHECK (
        processing_outcome IN ('APPLIED', 'SEMANTIC_DUPLICATE')
    );

COMMENT ON COLUMN notification_inbox.processing_outcome IS
    'Transport record result: a new business effect or a semantic duplicate no-op.';
COMMENT ON COLUMN notifications.effect_key IS
    'Version-1 PAYMENT_CAPTURED_NOTIFICATION identity: immutable capture ledger transaction ID.';
