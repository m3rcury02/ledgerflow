#!/usr/bin/env bash
set -euo pipefail

psql \
  --set=ON_ERROR_STOP=1 \
  --set=keycloak_database="$KEYCLOAK_DB_NAME" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" \
  --set=keycloak_user="$KEYCLOAK_DB_USER" \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L',
  :'keycloak_user',
  :'keycloak_password'
) WHERE NOT EXISTS (
  SELECT FROM pg_catalog.pg_roles WHERE rolname = :'keycloak_user'
) \gexec

SELECT format(
  'CREATE DATABASE %I OWNER %I',
  :'keycloak_database',
  :'keycloak_user'
) WHERE NOT EXISTS (
  SELECT FROM pg_catalog.pg_database WHERE datname = :'keycloak_database'
) \gexec
SQL

