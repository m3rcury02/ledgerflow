#!/usr/bin/env bash
# Database lock contention experiment. See docs/performance-experiments.md for the full
# narrative. The original hypothesis was that a concurrent idempotent replay would queue
# behind a `SELECT ... FOR UPDATE` lock on the backing row. Two rounds of measurement (first
# against `orders`, then against the exact `idempotency_records` row the replay path reads)
# both showed replay returning in ~30ms while the row was locked for 5s — Postgres never
# blocks a plain (non-locking) SELECT behind another session's row lock under READ
# COMMITTED, and the replay path is, correctly, a plain read. That is a real, positive
# finding about the application (idempotent replay cannot be starved by a writer holding a
# row lock), not a broken test.
#
# This version proves both things directly instead of relying on one ambiguous number:
#   1. Two raw SQL sessions on the exact same idempotency_records row DO serialize — the
#      second FOR UPDATE genuinely queues behind the first (the underlying Postgres
#      mechanism works as expected in this schema).
#   2. The application's idempotent replay path is unaffected by that same lock and returns
#      immediately (the read path's real, evidence-based behavior).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:18080}
ACCESS_TOKEN=${ACCESS_TOKEN:?ACCESS_TOKEN is required}
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
LOCK_HOLD_SECONDS=${LOCK_HOLD_SECONDS:-5}

KEY="lock-contention-$(python3 -c 'import uuid; print(uuid.uuid4())')"
BODY='{"clientReference":"'"$KEY"'","amount":{"amountMinor":150000,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}'

echo "[db-lock-contention] Creating the order whose idempotency_records row will be locked"
CREATE_RESPONSE=$(curl --fail --silent --show-error "$BASE_URL/api/v1/orders" \
  --request POST \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $KEY" \
  --header "X-Correlation-Id: $KEY" \
  --data "$BODY")
ORDER_ID=$(echo "$CREATE_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['orderId'])")

IFS=$'\t' read -r SCOPE OPERATION KEY_HASH_HEX < <(
  psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
    --quiet --no-psqlrc --tuples-only --no-align --field-separator $'\t' --command \
    "SELECT principal_scope, operation, encode(key_hash, 'hex') FROM idempotency_records WHERE resource_id = '$ORDER_ID';"
)
LOCK_PREDICATE="principal_scope = '$SCOPE' AND operation = '$OPERATION' AND key_hash = decode('$KEY_HASH_HEX', 'hex')"

echo "[db-lock-contention] Part 1: two raw SQL sessions on the same row"
psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
  --quiet --no-psqlrc <<SQL &
BEGIN;
SELECT * FROM idempotency_records WHERE $LOCK_PREDICATE FOR UPDATE;
SELECT pg_sleep($LOCK_HOLD_SECONDS);
COMMIT;
SQL
FIRST_SESSION_PID=$!
sleep 1

SQL_START_MS=$(( $(date +%s%N) / 1000000 ))
psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
  --quiet --no-psqlrc <<SQL
BEGIN;
SELECT * FROM idempotency_records WHERE $LOCK_PREDICATE FOR UPDATE;
COMMIT;
SQL
SQL_ELAPSED_MS=$(( $(date +%s%N) / 1000000 - SQL_START_MS ))
wait "$FIRST_SESSION_PID"
echo "[db-lock-contention] Second FOR UPDATE session waited ${SQL_ELAPSED_MS}ms (lock held ${LOCK_HOLD_SECONDS}s)"

echo "[db-lock-contention] Part 2: application idempotent replay against the same lock"
psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
  --quiet --no-psqlrc <<SQL &
BEGIN;
SELECT * FROM idempotency_records WHERE $LOCK_PREDICATE FOR UPDATE;
SELECT pg_sleep($LOCK_HOLD_SECONDS);
COMMIT;
SQL
LOCK_PID=$!
sleep 1

REPLAY_START_MS=$(( $(date +%s%N) / 1000000 ))
REPLAY_RESPONSE=$(curl --fail --silent --show-error "$BASE_URL/api/v1/orders" \
  --request POST \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $KEY" \
  --header "X-Correlation-Id: $KEY-replay" \
  --data "$BODY")
REPLAY_ELAPSED_MS=$(( $(date +%s%N) / 1000000 - REPLAY_START_MS ))
REPLAY_ORDER_ID=$(echo "$REPLAY_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['orderId'])")
wait "$LOCK_PID"

echo "[db-lock-contention] Application replay elapsed: ${REPLAY_ELAPSED_MS}ms (lock held ${LOCK_HOLD_SECONDS}s)"
echo "[db-lock-contention] Original order: $ORDER_ID, replay order: $REPLAY_ORDER_ID"

if [[ "$ORDER_ID" != "$REPLAY_ORDER_ID" ]]; then
  echo "[db-lock-contention] FAIL: replay returned a different order id" >&2
  exit 1
fi
if ((SQL_ELAPSED_MS < (LOCK_HOLD_SECONDS - 1) * 1000)); then
  echo "[db-lock-contention] FAIL: a second FOR UPDATE session did not queue behind the first — Postgres locking is not behaving as expected in this schema" >&2
  exit 1
fi
if ((REPLAY_ELAPSED_MS > 1000)); then
  echo "[db-lock-contention] FAIL: application replay took ${REPLAY_ELAPSED_MS}ms — expected it to stay non-blocking against a row lock held by another session" >&2
  exit 1
fi

echo "[db-lock-contention] PASS: raw SQL sessions correctly serialize on the row (${SQL_ELAPSED_MS}ms), and the application's idempotent replay is a non-blocking read unaffected by that same lock (${REPLAY_ELAPSED_MS}ms)"
