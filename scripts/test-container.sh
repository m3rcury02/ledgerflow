#!/bin/bash
set -ex

echo "Inspecting user..."
docker inspect ledgerflow:latest --format '{{.Config.User}}' | grep ledgerflow

echo "Starting container..."
# Stop and remove any existing test container
docker rm -f ledgerflow-test || true

docker run -d --rm --name ledgerflow-test \
  --read-only \
  -p 8082:8082 \
  --network ledgerflow-local_ledgerflow \
  -e LEDGERFLOW_DB_URL=jdbc:postgresql://postgres:5432/ledgerflow \
  -e LEDGERFLOW_DB_USERNAME=ledgerflow \
  -e LEDGERFLOW_DB_PASSWORD='change-me-local-postgres' \
  -e LEDGERFLOW_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e LEDGERFLOW_OAUTH2_AUDIENCE=ledgerflow-api \
  -e LEDGERFLOW_OAUTH2_ISSUER=http://keycloak:8080/realms/ledgerflow \
  -e LEDGERFLOW_OAUTH2_JWK_SET_URI=http://keycloak:8080/realms/ledgerflow/protocol/openid-connect/certs \
  -e LEDGERFLOW_PAYMENT_PROVIDER_BASE_URL=http://127.0.0.1:8090 \
  -e LEDGERFLOW_MANAGEMENT_PORT=8082 \
  ledgerflow:latest

echo "Waiting for health endpoint..."
for i in {1..30}; do
  status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health || echo "000")
  if [[ "$status" == "200" || "$status" == "401" ]]; then
    echo "Health endpoint is up with status $status!"
    break
  fi
  sleep 2
done

curl -v http://localhost:8082/actuator/health || true

echo "Stopping gracefully..."
docker stop --time 10 ledgerflow-test

echo "Scanning image..."
docker run --rm \
  --volume /var/run/docker.sock:/var/run/docker.sock:ro \
  --volume ledgerflow-trivy-cache:/root/.cache/trivy \
  ghcr.io/aquasecurity/trivy:0.72.0 \
  image --timeout 15m --severity HIGH,CRITICAL --ignore-unfixed --exit-code 0 ledgerflow:latest

echo "Inspecting image history..."
docker history ledgerflow:latest
