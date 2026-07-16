#!/usr/bin/env bash
set -euo pipefail

echo "Starting Kubernetes smoke test..."

# Port-forward the public API
kubectl port-forward service/ledgerflow 8080:8080 > /dev/null 2>&1 &
PF_PID=$!
sleep 3

echo "Testing Public API (Swagger UI)..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/swagger-ui/index.html || echo "000")
if [ "$HTTP_STATUS" = "200" ] || [ "$HTTP_STATUS" = "401" ] || [ "$HTTP_STATUS" = "302" ]; then
  echo "Public API is accessible (HTTP $HTTP_STATUS)."
else
  echo "Public API test failed with HTTP $HTTP_STATUS."
  kill $PF_PID || true
  exit 1
fi

kill $PF_PID || true
sleep 1

# Port-forward the management port
kubectl port-forward service/ledgerflow 9000:9000 > /dev/null 2>&1 &
PF_PID=$!
sleep 3

echo "Testing Management Port (Actuator Health)..."
if curl -s http://localhost:9000/actuator/health/readiness | grep -q "UP"; then
  echo "Management API is accessible and healthy."
else
  echo "Management API test failed."
  kill $PF_PID || true
  exit 1
fi

kill $PF_PID || true

echo "Smoke test passed successfully."
