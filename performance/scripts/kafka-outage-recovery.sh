#!/usr/bin/env bash
# Kafka outage and recovery experiment. See docs/performance-experiments.md. Stops the
# local Kafka Compose service, proves order creation still succeeds (capture is durable
# before Kafka is ever involved) while the outbox backlog grows, then restarts Kafka and
# times the drain. Only touches the Kafka container; every other Compose dependency and
# all local data stay untouched.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ENV_FILE=${LEDGERFLOW_ENV_FILE:-"$ROOT_DIR/.env"}
BASE_URL=${BASE_URL:-http://localhost:18080}
ACCESS_TOKEN=${ACCESS_TOKEN:?ACCESS_TOKEN is required}
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
ORDERS_DURING_OUTAGE=${ORDERS_DURING_OUTAGE:-10}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-60}

compose() {
  docker compose --env-file "$ENV_FILE" --file "$ROOT_DIR/compose.yaml" "$@"
}

pending_count() {
  psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
    --quiet --no-psqlrc --tuples-only --no-align \
    --command "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';"
}

BEFORE=$(pending_count)
echo "[kafka-outage-recovery] Unpublished outbox rows before outage: $BEFORE"

echo "[kafka-outage-recovery] Stopping kafka"
compose stop kafka

FAILURES=0
for i in $(seq 1 "$ORDERS_DURING_OUTAGE"); do
  KEY="kafka-outage-$i-$(python3 -c 'import uuid; print(uuid.uuid4())')"
  STATUS=$(curl --silent --output /dev/null --write-out "%{http_code}" "$BASE_URL/api/v1/orders" \
    --request POST \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --header "Content-Type: application/json" \
    --header "Idempotency-Key: $KEY" \
    --header "X-Correlation-Id: $KEY" \
    --data '{"clientReference":"'"$KEY"'","amount":{"amountMinor":100000,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}')
  if [[ "$STATUS" != "201" ]]; then
    echo "[kafka-outage-recovery] order $i got HTTP $STATUS (expected 201)" >&2
    FAILURES=$((FAILURES + 1))
  fi
done
echo "[kafka-outage-recovery] Orders created during outage: $ORDERS_DURING_OUTAGE, non-201 responses: $FAILURES"

DURING=$(pending_count)
echo "[kafka-outage-recovery] Unpublished outbox rows during outage: $DURING"

echo "[kafka-outage-recovery] Starting kafka"
compose start kafka
compose up --detach --wait --wait-timeout 60 kafka

echo "[kafka-outage-recovery] Waiting for the outbox backlog to drain"
START_MS=$(( $(date +%s%N) / 1000000 ))
DEADLINE=$((START_MS + DRAIN_TIMEOUT_SECONDS * 1000))
while true; do
  REMAINING=$(pending_count)
  NOW_MS=$(( $(date +%s%N) / 1000000 ))
  if [[ "$REMAINING" == "0" ]]; then
    break
  fi
  if ((NOW_MS > DEADLINE)); then
    echo "[kafka-outage-recovery] FAIL: $REMAINING rows still unpublished after ${DRAIN_TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  sleep 1
done
END_MS=$(( $(date +%s%N) / 1000000 ))
DRAIN_MS=$((END_MS - START_MS))

echo "[kafka-outage-recovery] Backlog drained in ${DRAIN_MS}ms"
if ((FAILURES > 0)); then
  echo "[kafka-outage-recovery] FAIL: $FAILURES order creations failed during the outage" >&2
  exit 1
fi
echo "[kafka-outage-recovery] PASS: order creation stayed available during the outage and the backlog fully drained after recovery"
