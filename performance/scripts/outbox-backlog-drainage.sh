#!/usr/bin/env bash
# Outbox backlog drainage experiment. See docs/performance-experiments.md. Starts the
# performance/compose.perf.yaml "app" service with the outbox publisher disabled, fills a
# backlog of captured payments whose outbox rows stay PENDING, recreates it with the
# publisher enabled, and times the drain to zero.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
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
  echo "[outbox-backlog-drainage] FAIL: application did not become ready" >&2
  exit 1
}

echo "[outbox-backlog-drainage] Phase 1: recreating the app with the publisher disabled"
export LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=false
compose up --detach --force-recreate app mock-provider >/dev/null
wait_ready

echo "[outbox-backlog-drainage] Filling the backlog"
"$ROOT_DIR/performance/scripts/run-k6.sh" "$ROOT_DIR/performance/scenarios/outbox-backlog-fill.js"

BACKLOG=$(psql_scalar "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';")
echo "[outbox-backlog-drainage] Backlog size with the publisher disabled: $BACKLOG"

echo "[outbox-backlog-drainage] Phase 2: recreating the app with the publisher enabled"
export LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=true
START_MS=$(( $(date +%s%N) / 1000000 ))
compose up --detach --force-recreate app mock-provider >/dev/null
wait_ready

DEADLINE=$((START_MS + DRAIN_TIMEOUT_SECONDS * 1000))
while true; do
  REMAINING=$(psql_scalar "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';")
  [[ "$REMAINING" == "0" ]] && break
  if (($(( $(date +%s%N) / 1000000 )) > DEADLINE)); then
    echo "[outbox-backlog-drainage] FAIL: $REMAINING rows still unpublished after ${DRAIN_TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  sleep 1
done
END_MS=$(( $(date +%s%N) / 1000000 ))
DRAIN_MS=$((END_MS - START_MS))

echo "[outbox-backlog-drainage] PASS: backlog of $BACKLOG rows drained in ${DRAIN_MS}ms after the publisher restarted"
