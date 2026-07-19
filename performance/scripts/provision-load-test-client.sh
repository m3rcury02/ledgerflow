#!/usr/bin/env bash
# Idempotently provisions a pool of service-account-only Keycloak clients used only by the
# performance and failure experiments in performance/. A pool, not one client, exists
# because the application enforces a documented per-authenticated-subject write rate limit
# (README.md "Create Order API": 60 create attempts per subject per minute by default) —
# a single shared identity trips that abuse-prevention control well below any of this
# milestone's realistic throughput targets, which is a load-generator artifact, not a
# system capacity finding. Distributing load across several identities models multiple
# customers instead. None of this is part of the MVP's committed realm definition
# (infra/keycloak/ledgerflow-realm.json), which intentionally ships with no users,
# passwords, or client secrets, and this script must not change it.
#
# On success, prints one line per pooled client to stdout:
#   LOAD_TEST_CLIENT_SECRET_<n>=<secret>
# All other script output goes to stderr so callers can safely capture stdout.
set -euo pipefail

KEYCLOAK_BASE_URL=${KEYCLOAK_BASE_URL:-http://localhost:8081}
KEYCLOAK_REALM=${KEYCLOAK_REALM:-ledgerflow}
KEYCLOAK_ADMIN_USER=${KEYCLOAK_ADMIN_USER:-admin}
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-change-me-local-keycloak-admin}
LOAD_TEST_CLIENT_PREFIX=${LOAD_TEST_CLIENT_PREFIX:-ledgerflow-load-test}
LOAD_TEST_CLIENT_POOL_SIZE=${LOAD_TEST_CLIENT_POOL_SIZE:-10}

log() { echo "[provision-load-test-client] $*" >&2; }

admin_token() {
  curl --fail --silent --show-error \
    --request POST "$KEYCLOAK_BASE_URL/realms/master/protocol/openid-connect/token" \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --data "grant_type=password" \
    --data "client_id=admin-cli" \
    --data "username=$KEYCLOAK_ADMIN_USER" \
    --data "password=$KEYCLOAK_ADMIN_PASSWORD" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}

realm_api() {
  local method=$1 path=$2 data=${3:-}
  local args=(--fail --silent --show-error --request "$method" \
    "$KEYCLOAK_BASE_URL/admin/realms/$KEYCLOAK_REALM$path" \
    --header "Authorization: Bearer $(admin_token)" \
    --header "Content-Type: application/json")
  if [[ -n "$data" ]]; then
    args+=(--data "$data")
  fi
  curl "${args[@]}"
}

provision_one() {
  local client_id=$1
  local client_uuid
  client_uuid=$(realm_api GET "/clients?clientId=$client_id" \
    | python3 -c "import json,sys; rows=json.load(sys.stdin); print(rows[0]['id'] if rows else '')")

  if [[ -z "$client_uuid" ]]; then
    log "Creating client '$client_id'"
    realm_api POST "/clients" "$(python3 -c "
import json
print(json.dumps({
    'clientId': '$client_id',
    'name': 'LedgerFlow performance/failure experiment client (local only)',
    'protocol': 'openid-connect',
    'publicClient': False,
    'serviceAccountsEnabled': True,
    'standardFlowEnabled': False,
    'directAccessGrantsEnabled': False,
    'implicitFlowEnabled': False,
    'defaultClientScopes': ['ledgerflow-api-audience'],
    'optionalClientScopes': ['ledgerflow.orders.read', 'ledgerflow.orders.write'],
}))
")" >/dev/null

    client_uuid=$(realm_api GET "/clients?clientId=$client_id" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")

    realm_api POST "/clients/$client_uuid/protocol-mappers/models" '{
      "name": "realm-roles",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-usermodel-realm-role-mapper",
      "consentRequired": false,
      "config": {
        "claim.name": "realm_access.roles",
        "jsonType.label": "String",
        "multivalued": "true",
        "access.token.claim": "true",
        "id.token.claim": "false",
        "userinfo.token.claim": "false"
      }
    }' >/dev/null

    local sa_user_id customer_role
    sa_user_id=$(realm_api GET "/clients/$client_uuid/service-account-user" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
    customer_role=$(realm_api GET "/roles/customer")
    realm_api POST "/users/$sa_user_id/role-mappings/realm" "[$customer_role]" >/dev/null
  else
    log "Client '$client_id' already exists ($client_uuid); reusing it"
  fi

  realm_api GET "/clients/$client_uuid/client-secret" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['value'])"
}

for i in $(seq 0 $((LOAD_TEST_CLIENT_POOL_SIZE - 1))); do
  secret=$(provision_one "${LOAD_TEST_CLIENT_PREFIX}-${i}")
  echo "LOAD_TEST_CLIENT_SECRET_${i}=${secret}"
done

# Dedicated to idempotency-contention.js, which must use exactly one identity for every
# concurrent request to actually contend on the same idempotency record (idempotency is
# scoped per (principal_scope, operation, key_hash) — see
# application/src/main/resources/db/migration/V001__create_orders_and_idempotency.sql). It
# is a client of its own, not pool index 0, so its rate-limit budget is never shared with
# whichever VU in another scenario happens to round-robin onto index 0.
contention_secret=$(provision_one "${LOAD_TEST_CLIENT_PREFIX}-contention")
echo "LOAD_TEST_CLIENT_SECRET_CONTENTION=${contention_secret}"

# Dedicated to burst-traffic.js, which deliberately floods past the per-subject write rate
# limit to prove the application degrades to 429, not 5xx. That is intentional damage to
# this one identity's rate-limit budget; giving it its own client keeps every other
# scenario's "clean" throughput measurement from inheriting an already-exhausted budget.
burst_secret=$(provision_one "${LOAD_TEST_CLIENT_PREFIX}-burst")
echo "LOAD_TEST_CLIENT_SECRET_BURST=${burst_secret}"

log "Pool of $LOAD_TEST_CLIENT_POOL_SIZE clients (plus dedicated contention/burst clients) ready"
