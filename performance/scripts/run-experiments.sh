#!/usr/bin/env bash
# Orchestrates every performance and failure experiment in performance/ against a real
# local LedgerFlow stack. See docs/performance-experiments.md for what each scenario proves.
#
# Prerequisites (not managed by this script): `scripts/dev-up` dependencies healthy.
#
# The application and the mock payment provider run as real containers
# (performance/compose.perf.yaml), not bare local processes: this environment's Docker only
# makes container-published ports reachable consistently from both this shell and other
# containers (k6, kcat) — a bare `java -jar` process bound directly in this shell is not
# reachable from a container, and a container's directly-bound port is not reachable from
# this shell. See that file's comments and docs/performance-experiments.md for the full
# explanation.
#
# This script never touches scripts/dev-up's shared dependency containers except to
# stop/start the kafka service for the Kafka-outage scenario, and it never deletes any
# Compose volume.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RESULTS_DIR="$ROOT_DIR/performance/results"
mkdir -p "$RESULTS_DIR"
# Gradle resolves settings.gradle.kts from the caller's working directory, not gradlew's own
# location; run from ROOT_DIR explicitly so this script behaves the same regardless of the
# directory it was launched from.
cd "$ROOT_DIR"

export LEDGERFLOW_ROOT="$ROOT_DIR"
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5433}
PGDATABASE=${PGDATABASE:-ledgerflow}
PGUSER=${PGUSER:-ledgerflow}
export PGPASSWORD=${PGPASSWORD:-change-me-local-postgres}
export KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-change-me-local-keycloak-admin}
# Shell-based scripts below (curl/psql run directly in this script, not in a container)
# default to the application's published host port (18080) themselves; k6 (run-k6.sh)
# reaches it container-to-container instead — see run-k6.sh's comment for the
# evidence-backed reason those two paths are deliberately different.
POOL_SIZE=${LOAD_TEST_CLIENT_POOL_SIZE:-10}
TOKENS_JSON="$ROOT_DIR/performance/scenarios/tokens.json"
CONTENTION_TOKEN_JSON="$ROOT_DIR/performance/scenarios/contention-token.json"
BURST_TOKEN_JSON="$ROOT_DIR/performance/scenarios/burst-token.json"

log() { echo "[run-experiments] $*"; }

compose() {
  docker compose --env-file "$ROOT_DIR/.env" \
    -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/performance/compose.perf.yaml" \
    --project-name ledgerflow-local "$@"
}

# Mints one token per pooled client (see provision-load-test-client.sh for why a pool
# exists) and writes performance/scenarios/tokens.json for k6's lib/client.js to read.
# Tokens live 300s (the realm's default access-token lifespan); called fresh before every
# scenario so nothing runs against an expired token.
mint_token_pool() {
  local tokens=() i secret token
  for i in $(seq 0 $((POOL_SIZE - 1))); do
    secret_var="LOAD_TEST_CLIENT_SECRET_${i}"
    secret="${!secret_var}"
    token=$(curl --fail --silent --show-error \
      --request POST "http://localhost:8081/realms/ledgerflow/protocol/openid-connect/token" \
      --header "Content-Type: application/x-www-form-urlencoded" \
      --data "grant_type=client_credentials" \
      --data "client_id=ledgerflow-load-test-${i}" \
      --data "client_secret=$secret" \
      --data "scope=ledgerflow.orders.write ledgerflow.orders.read" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
    tokens+=("\"$token\"")
  done
  printf '[%s]' "$(IFS=,; echo "${tokens[*]}")" >"$TOKENS_JSON"
}

# Mints the dedicated contention client's token (see provision-load-test-client.sh) and
# writes performance/scenarios/contention-token.json for idempotency-contention.js,
# including the shared Idempotency-Key and clientReference every VU will send — generated
# here (not in k6) so this shell can run the authoritative "exactly one order" database
# check against the same values after the k6 run exits.
CONTENTION_CLIENT_REFERENCE=""
mint_contention_token() {
  local token
  token=$(curl --fail --silent --show-error \
    --request POST "http://localhost:8081/realms/ledgerflow/protocol/openid-connect/token" \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --data "grant_type=client_credentials" \
    --data "client_id=ledgerflow-load-test-contention" \
    --data "client_secret=$LOAD_TEST_CLIENT_SECRET_CONTENTION" \
    --data "scope=ledgerflow.orders.write ledgerflow.orders.read" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
  local key="contention-$(python3 -c 'import uuid; print(uuid.uuid4())')"
  CONTENTION_CLIENT_REFERENCE="contention-ref-$(python3 -c 'import uuid; print(uuid.uuid4())')"
  printf '{"token":"%s","key":"%s","clientReference":"%s"}' \
    "$token" "$key" "$CONTENTION_CLIENT_REFERENCE" >"$CONTENTION_TOKEN_JSON"
}

# Mints the dedicated burst client's token and writes performance/scenarios/burst-token.json
# for burst-traffic.js.
mint_burst_token() {
  local token
  token=$(curl --fail --silent --show-error \
    --request POST "http://localhost:8081/realms/ledgerflow/protocol/openid-connect/token" \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --data "grant_type=client_credentials" \
    --data "client_id=ledgerflow-load-test-burst" \
    --data "client_secret=$LOAD_TEST_CLIENT_SECRET_BURST" \
    --data "scope=ledgerflow.orders.write ledgerflow.orders.read" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
  printf '{"token":"%s"}' "$token" >"$BURST_TOKEN_JSON"
}

# One token from the pool, for the shell-script scenarios that only ever issue a handful
# of requests (well under the per-subject rate limit) and don't need the full pool.
mint_single_token() {
  curl --fail --silent --show-error \
    --request POST "http://localhost:8081/realms/ledgerflow/protocol/openid-connect/token" \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --data "grant_type=client_credentials" \
    --data "client_id=ledgerflow-load-test-0" \
    --data "client_secret=$LOAD_TEST_CLIENT_SECRET_0" \
    --data "scope=ledgerflow.orders.write ledgerflow.orders.read" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}

wait_ready() {
  for _ in $(seq 1 40); do
    curl --silent --fail --output /dev/null "http://localhost:8082/actuator/health/readiness" 2>/dev/null && return 0
    sleep 1
  done
  log "FAIL: application did not become ready"
  compose logs app | tail -80
  exit 1
}

# Readiness only means the Spring context is up, not that the JVM is JIT-warmed or that
# HikariCP has established its pool: a k6 run started immediately after wait_ready measured
# p(95) 517-749ms on normal-traffic.js across repeated cold starts, dropping to 100-150ms
# once ~90-190 throwaway requests had already run (docs/performance-experiments.md,
# "Normal traffic" bottleneck/optimization/rerun). This warmup absorbs that cold start so
# every scenario's numbers reflect steady-state application behavior.
warm_up() {
  local token
  token=$(mint_single_token)
  for i in $(seq 1 30); do
    curl --silent --output /dev/null "http://localhost:18080/api/v1/orders" \
      --request POST --header "Authorization: Bearer $token" --header "Content-Type: application/json" \
      --header "Idempotency-Key: warmup-$i-$(python3 -c 'import uuid; print(uuid.uuid4())')" \
      --header "X-Correlation-Id: warmup-$i" \
      --data '{"clientReference":"warmup-'$i'","amount":{"amountMinor":100,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}' \
      || true
  done
}

cleanup() {
  compose down app mock-provider >/dev/null 2>&1 || true
  rm -f "$TOKENS_JSON" "$CONTENTION_TOKEN_JSON" "$BURST_TOKEN_JSON"
}
trap cleanup EXIT

log "Building the application image"
docker build --tag ledgerflow:local "$ROOT_DIR" >"$RESULTS_DIR/docker-build.log" 2>&1

log "Compiling the integrationTest classes the mock provider runs from"
"$ROOT_DIR/gradlew" --no-daemon :application:compileIntegrationTestJava --console=plain \
  >"$RESULTS_DIR/gradle-compile.log" 2>&1
export MOCK_PROVIDER_CLASSPATH
MOCK_PROVIDER_CLASSPATH=$("$ROOT_DIR/gradlew" --no-daemon :application:printMockProviderClasspath \
  --console=plain -q | grep MOCK_PROVIDER_CLASSPATH | cut -d= -f2-)

log "Provisioning a pool of $POOL_SIZE Keycloak load-test clients"
while IFS='=' read -r key value; do
  [[ -n "$key" ]] && export "$key=$value"
done < <("$ROOT_DIR/performance/scripts/provision-load-test-client.sh" | grep LOAD_TEST_CLIENT_SECRET_)

run_k6() {
  mint_token_pool
  "$ROOT_DIR/performance/scripts/run-k6.sh" "$1"
}

log "Starting app + mock-provider with default configuration"
compose down app mock-provider >/dev/null 2>&1 || true
compose up --detach --force-recreate app mock-provider
wait_ready
log "Warming up the JVM/connection pool before measuring anything"
warm_up

log "=== normal-traffic ==="
run_k6 "$ROOT_DIR/performance/scenarios/normal-traffic.js"

log "=== burst-traffic ==="
mint_burst_token
run_k6 "$ROOT_DIR/performance/scenarios/burst-traffic.js"

log "=== idempotency-contention ==="
mint_contention_token
run_k6 "$ROOT_DIR/performance/scenarios/idempotency-contention.js"
ORDER_COUNT_FOR_CONTENTION_REF=$(psql --host "$PGHOST" --port "$PGPORT" --dbname "$PGDATABASE" --username "$PGUSER" \
  --quiet --no-psqlrc --tuples-only --no-align --command \
  "SELECT count(*) FROM orders WHERE client_reference = '$CONTENTION_CLIENT_REFERENCE';")
log "Authoritative check: orders rows for the shared client reference = $ORDER_COUNT_FOR_CONTENTION_REF (must be 1)"
if [[ "$ORDER_COUNT_FOR_CONTENTION_REF" != "1" ]]; then
  log "FAIL: expected exactly 1 order for the contended client reference, found $ORDER_COUNT_FOR_CONTENTION_REF"
  exit 1
fi

log "=== unique-key-growth ==="
run_k6 "$ROOT_DIR/performance/scenarios/unique-key-growth.js"

log "=== slow-provider ==="
run_k6 "$ROOT_DIR/performance/scenarios/slow-provider.js"

log "=== db-lock-contention ==="
ACCESS_TOKEN=$(mint_single_token) "$ROOT_DIR/performance/scripts/db-lock-contention.sh" \
  2>&1 | tee "$RESULTS_DIR/db-lock-contention.log"

log "=== kafka-outage-recovery ==="
ACCESS_TOKEN=$(mint_single_token) "$ROOT_DIR/performance/scripts/kafka-outage-recovery.sh" \
  2>&1 | tee "$RESULTS_DIR/kafka-outage-recovery.log"

log "=== duplicate-event-delivery ==="
ACCESS_TOKEN=$(mint_single_token) "$ROOT_DIR/performance/scripts/duplicate-event-delivery.sh" \
  2>&1 | tee "$RESULTS_DIR/duplicate-event-delivery.log"

log "=== worker-restart ==="
ACCESS_TOKEN=$(mint_single_token) "$ROOT_DIR/performance/scripts/worker-restart.sh" \
  2>&1 | tee "$RESULTS_DIR/worker-restart.log"

log "=== outbox-backlog-drainage ==="
mint_token_pool
"$ROOT_DIR/performance/scripts/outbox-backlog-drainage.sh" \
  2>&1 | tee "$RESULTS_DIR/outbox-backlog-drainage.log"

log "Recreating the app with tightened provider timeouts for provider-timeout"
# overall must be >= max(connect, read) (the application validates this at startup), and
# all three must stay below the mock provider's fixed 1500ms TIMEOUT_RESPONSE_DELAY so a
# real client-perceived timeout actually happens.
export LEDGERFLOW_PAYMENT_PROVIDER_CONNECT_TIMEOUT=300ms
export LEDGERFLOW_PAYMENT_PROVIDER_READ_TIMEOUT=800ms
export LEDGERFLOW_PAYMENT_PROVIDER_OVERALL_TIMEOUT=1000ms
export LEDGERFLOW_PAYMENT_PROVIDER_ACTIVE_OPERATION_TIMEOUT=2s
compose up --detach --force-recreate app mock-provider
wait_ready
warm_up

log "=== provider-timeout ==="
run_k6 "$ROOT_DIR/performance/scenarios/provider-timeout.js"

unset LEDGERFLOW_PAYMENT_PROVIDER_CONNECT_TIMEOUT LEDGERFLOW_PAYMENT_PROVIDER_READ_TIMEOUT \
  LEDGERFLOW_PAYMENT_PROVIDER_OVERALL_TIMEOUT LEDGERFLOW_PAYMENT_PROVIDER_ACTIVE_OPERATION_TIMEOUT

log "All experiments finished. Results and logs are in performance/results/."
