\set ON_ERROR_STOP on
\set ECHO errors

-- Run only through the restricted bootstrap procedure in docs/aws-terraform-design.md. Passwords
-- arrive through process environment and psql \getenv; they are never command-line arguments,
-- SQL literals in this file, Terraform values, or shell output.
\getenv migration_password LEDGERFLOW_MIGRATION_DB_PASSWORD
\getenv api_password LEDGERFLOW_API_DB_PASSWORD
\getenv worker_password LEDGERFLOW_WORKER_DB_PASSWORD

\if :{?migration_password}
\else
  \echo 'LEDGERFLOW_MIGRATION_DB_PASSWORD is required'
  \quit 3
\endif
\if :{?api_password}
\else
  \echo 'LEDGERFLOW_API_DB_PASSWORD is required'
  \quit 3
\endif
\if :{?worker_password}
\else
  \echo 'LEDGERFLOW_WORKER_DB_PASSWORD is required'
  \quit 3
\endif

-- RDS logs DDL for operational evidence. Suppress statement logging only in this bootstrap
-- session before password-bearing CREATE/ALTER ROLE statements, then restore it immediately.
-- Otherwise PostgreSQL's DDL log would itself become a credential leak.
SET log_statement = 'none';

SELECT
  length(:'migration_password') >= 32
    AND length(:'api_password') >= 32
    AND length(:'worker_password') >= 32 AS password_lengths_valid,
  :'migration_password' <> :'api_password'
    AND :'migration_password' <> :'worker_password'
    AND :'api_password' <> :'worker_password' AS passwords_distinct
\gset

\if :password_lengths_valid
\else
  \echo 'Each database password must contain at least 32 characters'
  \quit 3
\endif
\if :passwords_distinct
\else
  \echo 'Migration, API, and worker passwords must be distinct'
  \quit 3
\endif

SELECT format(
  'CREATE ROLE ledgerflow_migrator LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'migration_password'
)
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledgerflow_migrator')
\gexec

SELECT format(
  'CREATE ROLE ledgerflow_api LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'api_password'
)
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledgerflow_api')
\gexec

SELECT format(
  'CREATE ROLE ledgerflow_worker LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'worker_password'
)
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledgerflow_worker')
\gexec

SELECT format(
  'ALTER ROLE ledgerflow_migrator PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'migration_password'
)
\gexec
SELECT format(
  'ALTER ROLE ledgerflow_api PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'api_password'
)
\gexec
SELECT format(
  'ALTER ROLE ledgerflow_worker PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'worker_password'
)
\gexec

RESET log_statement;

-- PostgreSQL requires the current database/schema owner to be able to SET ROLE to a new owner.
-- The RDS master has CREATEROLE/rds_superuser rather than true PostgreSQL superuser authority, so
-- make that capability explicit and temporary instead of relying on local-superuser behavior.
SELECT format('GRANT ledgerflow_migrator TO %I', current_user)
\gexec
SELECT format('ALTER DATABASE %I OWNER TO ledgerflow_migrator', current_database())
\gexec
ALTER SCHEMA public OWNER TO ledgerflow_migrator;
SELECT format('REVOKE ledgerflow_migrator FROM %I', current_user)
\gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database())
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), current_user)
\gexec
SELECT format(
  'GRANT CONNECT ON DATABASE %I TO ledgerflow_migrator, ledgerflow_api, ledgerflow_worker',
  current_database()
)
\gexec
GRANT USAGE ON SCHEMA public TO ledgerflow_api, ledgerflow_worker;

-- Runtime identities receive the DML the current API/worker processes require, but no DELETE,
-- TRUNCATE, REFERENCES, TRIGGER, CREATE, role-management, or schema-ownership privilege. Database
-- constraints and immutability triggers remain authoritative for financial/audit records.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO ledgerflow_api, ledgerflow_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerflow_api, ledgerflow_worker;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ledgerflow_api, ledgerflow_worker;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

ALTER DEFAULT PRIVILEGES FOR ROLE ledgerflow_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE ON TABLES TO ledgerflow_api, ledgerflow_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE ledgerflow_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO ledgerflow_api, ledgerflow_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE ledgerflow_migrator IN SCHEMA public
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE ledgerflow_migrator IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO ledgerflow_api, ledgerflow_worker;

ALTER ROLE ledgerflow_migrator SET search_path = public;
ALTER ROLE ledgerflow_api SET search_path = public;
ALTER ROLE ledgerflow_worker SET search_path = public;
