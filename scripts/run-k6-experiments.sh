#!/usr/bin/env bash
set -eo pipefail

echo "========================================="
echo " LedgerFlow Performance & Resilience"
echo "========================================="
echo "Date: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "Machine: $(uname -mnrs)"
echo "App Config: SPRING_PROFILES_ACTIVE=prod"
echo "========================================="

# Helper to wait for health
wait_for_health() {
  echo -n "Waiting for application to be healthy on port 9000..."
  while ! curl -s http://localhost:9000/actuator/health/readiness | grep -q '"status":"UP"'; do
    sleep 2
    echo -n "."
  done
  echo " UP!"
}

# Ensure application is running. For local tests, we assume docker-compose or helm is up.
# We will test against the public API on 8080 (k8s port-forward) or 8080 (compose).
wait_for_health

echo "Running safe local baseline experiments..."

# Export token if needed (we'll fetch a token from Keycloak)
# Keycloak is assumed to be on localhost:8080 or the internal network.
# For simplicity, if security is enabled, we need a token.
# Let's try to get a token:
# Get Admin Token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8081/realms/master/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=change-me-local-keycloak-admin" \
  -d "grant_type=password" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

# Create Client for Performance Testing
curl -s -X POST http://localhost:8081/admin/realms/ledgerflow/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"clientId":"ledgerflow-perf-client","enabled":true,"publicClient":false,"secret":"perf-secret","serviceAccountsEnabled":true,"defaultClientScopes":["ledgerflow-api-audience","ledgerflow.orders.write","ledgerflow.operations.retry","ledgerflow.orders.read"]}'

# Add Protocol Mapper for Realm Roles
CLIENT_UUID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8081/admin/realms/ledgerflow/clients?clientId=ledgerflow-perf-client" | grep -o '"id":"[^"]*' | head -n 1 | cut -d'"' -f4)
if [ -n "$CLIENT_UUID" ]; then
  curl -s -X POST http://localhost:8081/admin/realms/ledgerflow/clients/$CLIENT_UUID/protocol-mappers/models \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"realm roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","jsonType.label":"String","multivalued":"true","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"false"}}'
fi

# Assign 'customer' and 'operator' roles to the service account
SA_USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8081/admin/realms/ledgerflow/users?username=service-account-ledgerflow-perf-client" | grep -o '"id":"[^"]*' | head -n 1 | cut -d'"' -f4)
if [ -n "$SA_USER_ID" ]; then
  CUSTOMER_ROLE_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8081/admin/realms/ledgerflow/roles/customer" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
  OPERATOR_ROLE_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8081/admin/realms/ledgerflow/roles/operator" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
  curl -s -X POST "http://localhost:8081/admin/realms/ledgerflow/users/$SA_USER_ID/role-mappings/realm" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "[{\"id\":\"$CUSTOMER_ROLE_ID\",\"name\":\"customer\"},{\"id\":\"$OPERATOR_ROLE_ID\",\"name\":\"operator\"}]"
fi

# Get User Token via Client Credentials
export TOKEN=$(curl -s -X POST http://localhost:8081/realms/ledgerflow/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=ledgerflow-perf-client" \
  -d "client_secret=perf-secret" \
  -d "grant_type=client_credentials" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "WARNING: Could not fetch OAuth2 token. Running unauthenticated (might fail if secured)."
fi

mkdir -p performance/reports

echo "-----------------------------------------"
echo " Scenario 1: Normal Traffic Baseline"
echo "-----------------------------------------"
# Hypothesis: The system can handle 50 RPS of normal traffic with p(95) < 200ms.
k6 run --summary-export performance/reports/baseline-summary.json -e API_URL=http://localhost:8080 -e TOKEN="$TOKEN" performance/scenarios/baseline.js || echo "Baseline scenario failed thresholds!"

echo "-----------------------------------------"
echo " Scenario 2: Idempotency Contention"
echo "-----------------------------------------"
# Hypothesis: Concurrent identical requests will result in exactly 1 success and N idempotency reuses (200 OK).
k6 run --summary-export performance/reports/idempotency-summary.json -e API_URL=http://localhost:8080 -e TOKEN="$TOKEN" performance/scenarios/idempotency.js || echo "Idempotency scenario failed thresholds!"

echo "-----------------------------------------"
echo " Automated tests completed."
echo " Note: Destructive and long-running tests (Kafka outage, DB locks, burst to 10k) are marked as manual to protect the dev machine."
echo "========================================="
