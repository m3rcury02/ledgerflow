#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)


echo "Ensuring backend dependencies are running via Compose..."
docker compose --env-file "$ROOT_DIR/.env" --file "$ROOT_DIR/compose.yaml" up -d postgres kafka keycloak

echo "Building LedgerFlow image..."
"$ROOT_DIR/gradlew" --no-daemon :application:bootJar --console=plain
docker build -t ledgerflow:latest "$ROOT_DIR"

echo "Creating Kind cluster 'ledgerflow-cluster'..."
kind create cluster --name ledgerflow-cluster --wait 2m || true

echo "Attaching Kind to Compose network..."
docker network connect ledgerflow-local_ledgerflow ledgerflow-cluster-control-plane || true

echo "Loading image into Kind..."
kind load docker-image ledgerflow:latest --name ledgerflow-cluster

echo "Deploying LedgerFlow..."
helm upgrade --install ledgerflow "$ROOT_DIR/deploy/helm/ledgerflow" \
  --set env.SPRING_DATASOURCE_URL="jdbc:postgresql://postgres:5432/ledgerflow" \
  --set env.SPRING_DATASOURCE_USERNAME="ledgerflow" \
  --set env.SPRING_DATASOURCE_PASSWORD="change-me-local-postgres" \
  --set env.SPRING_KAFKA_BOOTSTRAP_SERVERS="kafka:9092" \
  --set env.LEDGERFLOW_OAUTH2_ISSUER="http://keycloak:8080/realms/ledgerflow" \
  --set env.LEDGERFLOW_OAUTH2_JWK_SET_URI="http://keycloak:8080/realms/ledgerflow/protocol/openid-connect/certs" \
  --wait --timeout 5m

echo "Kind cluster setup complete and LedgerFlow is deployed and ready."
