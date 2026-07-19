#!/usr/bin/env bash
# Worker restart experiment. See docs/performance-experiments.md. Uses the same
# performance/compose.perf.yaml "app" service the rest of the suite runs (already the
# repository Dockerfile image, dogfooding Milestone 1), sends a burst of order-creation
# requests, hard-kills it with SIGKILL (a crash, not a graceful shutdown) mid-flight to
# interrupt in-flight outbox leases, recreates it, and proves every outbox row still
# reaches PUBLISHED and every accepted order is in a consistent terminal state.
# mock-provider shares app's network namespace (network_mode: service:app in the compose
# file), so it is recreated alongside app rather than surviving the kill on its own.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BASE_URL=${BASE_URL:-http://localhost:18080}
ACCESS_TOKEN=${ACCESS_TOKEN:?ACCESS_TOKEN is required}
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
ORDER_COUNT=${ORDER_COUNT:-15}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-60}

compose() {
  docker compose --env-file "$ROOT_DIR/.env" \
    -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/performance/compose.perf.yaml" \
    --project-name ledgerflow-local "$@"
}

psql_scalar() {
  psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
    --quiet --no-psqlrc --tuples-only --no-align --command "$1"
}

wait_ready() {
  for _ in $(seq 1 30); do
    curl --silent --fail --output /dev/null "http://localhost:8082/actuator/health/readiness" 2>/dev/null && return 0
    sleep 1
  done
  echo "[worker-restart] FAIL: application did not become ready" >&2
  exit 1
}

echo "[worker-restart] Confirming instance A is ready"
wait_ready

echo "[worker-restart] Sending $ORDER_COUNT order creations in the background"
KEYS_FILE=$(mktemp)
trap 'rm -f "$KEYS_FILE"' EXIT
for i in $(seq 1 "$ORDER_COUNT"); do
  KEY="worker-restart-$i-$(python3 -c 'import uuid; print(uuid.uuid4())')"
  echo "$KEY" >>"$KEYS_FILE"
  (curl --silent --output /dev/null "$BASE_URL/api/v1/orders" \
    --request POST \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --header "Content-Type: application/json" \
    --header "Idempotency-Key: $KEY" \
    --header "X-Correlation-Id: $KEY" \
    --data '{"clientReference":"'"$KEY"'","amount":{"amountMinor":120000,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}' &)
done

sleep 0.3
echo "[worker-restart] SIGKILL-ing the app container mid-flight (simulated crash, not graceful shutdown)"
compose kill --signal SIGKILL app >/dev/null 2>&1 || true
wait || true

echo "[worker-restart] Recreating instance B (and its mock-provider sidecar)"
compose up --detach --force-recreate app mock-provider >/dev/null
wait_ready
echo "[worker-restart] Instance B is ready"

echo "[worker-restart] Retrying every key (idempotent; safe if instance A already completed it)"
while IFS= read -r KEY; do
  curl --silent --output /dev/null "$BASE_URL/api/v1/orders" \
    --request POST \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --header "Content-Type: application/json" \
    --header "Idempotency-Key: $KEY" \
    --header "X-Correlation-Id: $KEY-retry" \
    --data '{"clientReference":"'"$KEY"'","amount":{"amountMinor":120000,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}' || true
done <"$KEYS_FILE"

echo "[worker-restart] Waiting for every outbox row to reach PUBLISHED (no stuck lease)"
START_MS=$(( $(date +%s%N) / 1000000 ))
DEADLINE=$((START_MS + DRAIN_TIMEOUT_SECONDS * 1000))
while true; do
  REMAINING=$(psql_scalar "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';")
  [[ "$REMAINING" == "0" ]] && break
  if (($(( $(date +%s%N) / 1000000 )) > DEADLINE)); then
    echo "[worker-restart] FAIL: $REMAINING outbox rows still not PUBLISHED after ${DRAIN_TIMEOUT_SECONDS}s" >&2
    psql_scalar "SELECT status, lease_owner, lease_until FROM outbox_events WHERE status <> 'PUBLISHED' LIMIT 5;" >&2
    exit 1
  fi
  sleep 1
done

INCONSISTENT=$(psql_scalar "
  SELECT count(*) FROM orders o
  JOIN payments p ON p.order_id = o.id
  WHERE o.status = 'COMPLETED' AND p.state <> 'CAPTURED';
")
if [[ "$INCONSISTENT" != "0" ]]; then
  echo "[worker-restart] FAIL: $INCONSISTENT orders are COMPLETED without a CAPTURED payment" >&2
  exit 1
fi

echo "[worker-restart] PASS: after a hard kill and restart, every outbox row published and no order/payment inconsistency was left behind"
