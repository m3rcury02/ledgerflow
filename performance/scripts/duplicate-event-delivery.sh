#!/usr/bin/env bash
# Duplicate Kafka event delivery experiment. See docs/performance-experiments.md. Captures
# the real payment-captured message the application just published, re-publishes the exact
# same key/value bytes as a genuine second delivery (new offset, same event_id inside the
# payload), and proves the notification business effect stays exactly one row per
# application/src/main/resources/db/migration/V005__create_notification_inbox_and_dead_letters.sql's
# uniqueness constraints. Uses a throwaway, pinned kcat container on the Compose network;
# nothing is installed or committed locally.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BASE_URL=${BASE_URL:-http://localhost:18080}
ACCESS_TOKEN=${ACCESS_TOKEN:?ACCESS_TOKEN is required}
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
KAFKA_NETWORK=${KAFKA_NETWORK:-ledgerflow-local_ledgerflow}
TOPIC=${TOPIC:-ledgerflow.payment-captured.v1}
KCAT_IMAGE="edenhill/kcat:1.7.1@sha256:8f16a5fed099931ce1122420b7473efe467ff9841d53680b99db25dd1723d711"

psql_scalar() {
  psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
    --quiet --no-psqlrc --tuples-only --no-align --command "$1"
}

KEY="dup-delivery-$(python3 -c 'import uuid; print(uuid.uuid4())')"
echo "[duplicate-event-delivery] Creating the order to be duplicated"
CREATE_RESPONSE=$(curl --fail --silent --show-error "$BASE_URL/api/v1/orders" \
  --request POST \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $KEY" \
  --header "X-Correlation-Id: $KEY" \
  --data '{"clientReference":"'"$KEY"'","amount":{"amountMinor":175000,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}')
PAYMENT_ID=$(echo "$CREATE_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['payment']['paymentId'])")
echo "[duplicate-event-delivery] payment_id=$PAYMENT_ID"

echo "[duplicate-event-delivery] Waiting for the outbox publisher to send it"
for _ in $(seq 1 20); do
  STATUS=$(psql_scalar "SELECT status FROM outbox_events WHERE aggregate_id = '$PAYMENT_ID' ORDER BY created_at DESC LIMIT 1;")
  [[ "$STATUS" == "PUBLISHED" ]] && break
  sleep 1
done
if [[ "$STATUS" != "PUBLISHED" ]]; then
  echo "[duplicate-event-delivery] FAIL: outbox event never reached PUBLISHED (status=$STATUS)" >&2
  exit 1
fi
sleep 2 # let the notification consumer process the original delivery before we duplicate it

BEFORE_COUNT=$(psql_scalar "SELECT count(*) FROM notifications WHERE payment_id = '$PAYMENT_ID';")
echo "[duplicate-event-delivery] notifications rows before duplicate: $BEFORE_COUNT"

echo "[duplicate-event-delivery] Capturing the real published message"
CAPTURE_DIR=$(mktemp --directory)
trap 'rm -rf "$CAPTURE_DIR"' EXIT
docker run --rm --network "$KAFKA_NETWORK" "$KCAT_IMAGE" \
  -b kafka:29092 -C -t "$TOPIC" -o -1 -c 1 -e -K: -f '%k:%s\n' \
  > "$CAPTURE_DIR/message.txt"

if [[ ! -s "$CAPTURE_DIR/message.txt" ]]; then
  echo "[duplicate-event-delivery] FAIL: could not read the just-published message back" >&2
  exit 1
fi

echo "[duplicate-event-delivery] Re-publishing the identical key/value as a genuine duplicate delivery"
docker run --rm --interactive --network "$KAFKA_NETWORK" "$KCAT_IMAGE" \
  -b kafka:29092 -P -t "$TOPIC" -K: < "$CAPTURE_DIR/message.txt"

echo "[duplicate-event-delivery] Waiting for the consumer to process (or safely no-op) the duplicate"
sleep 3

AFTER_COUNT=$(psql_scalar "SELECT count(*) FROM notifications WHERE payment_id = '$PAYMENT_ID';")
echo "[duplicate-event-delivery] notifications rows after duplicate: $AFTER_COUNT"

if [[ "$BEFORE_COUNT" != "1" || "$AFTER_COUNT" != "1" ]]; then
  echo "[duplicate-event-delivery] FAIL: expected exactly one notification row before and after (before=$BEFORE_COUNT after=$AFTER_COUNT)" >&2
  exit 1
fi

echo "[duplicate-event-delivery] PASS: duplicate delivery produced no additional notification row"
