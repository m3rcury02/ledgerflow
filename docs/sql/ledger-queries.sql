-- LedgerFlow read-only ledger inspection examples for PostgreSQL/psql.
-- Run with a database role that has SELECT only. Never use direct SQL to repair ledger data.

-- 1. Trial balance by currency. A healthy result has difference_minor = 0.
SELECT
    currency,
    COALESCE(sum(amount_minor) FILTER (WHERE side = 'D'), 0) AS debit_minor,
    COALESCE(sum(amount_minor) FILTER (WHERE side = 'C'), 0) AS credit_minor,
    COALESCE(sum(amount_minor) FILTER (WHERE side = 'D'), 0)
        - COALESCE(sum(amount_minor) FILTER (WHERE side = 'C'), 0) AS difference_minor
FROM ledger_entries
GROUP BY currency
ORDER BY currency;

-- 2. Account balances using the account's normal side.
SELECT
    a.code,
    a.account_type,
    a.currency,
    count(e.id) AS entry_count,
    COALESCE(
        sum(
            CASE
                WHEN a.account_type = 'ASSET' AND e.side = 'D' THEN e.amount_minor
                WHEN a.account_type = 'ASSET' AND e.side = 'C' THEN -e.amount_minor
                WHEN a.account_type = 'LIABILITY' AND e.side = 'C' THEN e.amount_minor
                WHEN a.account_type = 'LIABILITY' AND e.side = 'D' THEN -e.amount_minor
            END
        ),
        0
    ) AS balance_minor
FROM ledger_accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.code, a.account_type, a.currency
ORDER BY a.code;

-- 3. Set a payment ID in psql, then list its transaction history and audit metadata.
-- \set payment_id '00000000-0000-0000-0000-000000000000'
SELECT
    t.id AS transaction_id,
    t.journal_type,
    t.payment_id,
    t.order_id,
    t.reversal_of_transaction_id,
    t.description,
    t.correlation_id,
    t.created_by,
    t.posted_at,
    COALESCE(sum(e.amount_minor) FILTER (WHERE e.side = 'D'), 0) AS debit_minor,
    COALESCE(sum(e.amount_minor) FILTER (WHERE e.side = 'C'), 0) AS credit_minor
FROM ledger_transactions t
JOIN ledger_entries e ON e.transaction_id = t.id
WHERE t.payment_id = :'payment_id'::uuid
GROUP BY t.id
ORDER BY t.posted_at, t.id;

-- 4. Expand every entry for the same payment in posting order.
SELECT
    t.posted_at,
    t.id AS transaction_id,
    t.journal_type,
    a.code AS account_code,
    a.account_type,
    e.side,
    e.amount_minor,
    e.currency,
    e.created_at
FROM ledger_transactions t
JOIN ledger_entries e ON e.transaction_id = t.id
JOIN ledger_accounts a ON a.id = e.account_id
WHERE t.payment_id = :'payment_id'::uuid
ORDER BY t.posted_at, t.id, e.id;
