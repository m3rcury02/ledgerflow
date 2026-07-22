# AWS Database Identity and Migration Runbook

This runbook closes the design flaw in which long-running API and worker tasks received the RDS
master credential. It is a design and validation artifact only: LedgerFlow's Terraform has never
been applied, and these commands must not be run without an approved AWS environment, change
record, private administrative network path, and backup/restore evidence.

## Identity boundaries

| Identity | Where available | Allowed database capability |
| --- | --- | --- |
| RDS master | RDS-managed bootstrap secret only | Initial role/database ownership setup and emergency administration; never injected into ECS |
| `ledgerflow_migrator` | One-shot migration task only | Owns the database/schema and applies forward-only Flyway migrations |
| `ledgerflow_api` | API task execution role only | `SELECT`, `INSERT`, `UPDATE`, sequence use, and approved function execution; no DDL or destructive table privilege |
| `ledgerflow_worker` | Worker task execution role only | Same bounded DML envelope required by current recovery/outbox/notification work; distinct credential and IAM boundary |

The runtime roles deliberately have no `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, `CREATE`,
role-management, ownership, superuser, replication, or row-security-bypass privilege. Existing
database constraints and immutable-record triggers still govern financial and audit data. The API
and worker currently need the same broad table-level DML set because recovery spans payment,
ledger, messaging, notification, and operator tables; splitting table grants further requires a
tested repository-operation matrix rather than guesses.

## First deployment: fail closed

1. Keep `deploy_application_services = false`. The initial Terraform apply creates RDS, three
   empty customer-KMS-encrypted secret containers, task definitions, and IAM/network boundaries,
   but API/worker desired counts and API autoscaling minimum remain zero.
2. From a private, audited administrative session, obtain the RDS-managed bootstrap secret and
   generate three independent cryptographically random passwords in memory. Disable shell tracing
   first. Never put passwords in Terraform variables, command arguments, files, tickets, logs, or
   shell history.
3. Export the three values only to the bootstrap process environment and run
   `deploy/terraform/aws/sql/bootstrap-database-identities.sql` through `psql` as the RDS bootstrap
   user. The script temporarily disables PostgreSQL statement logging only around password-bearing
   role statements so `log_statement=ddl` cannot leak credentials, restores logging immediately,
   grants the migration role to the RDS bootstrap user only for ownership transfer and revokes that
   membership immediately, establishes default privileges, and is safe to rerun for coordinated
   rotation.
4. Write each `{ "username": ..., "password": ... }` document to its matching Secrets Manager
   container through standard input, not a CLI argument. Clear all secret-bearing shell variables.
5. Register the candidate image under `migration_image_tag`, run the one-shot migration task, wait
   for it to stop, and require container exit code `0`. The image's
   `--ledgerflow.migration-only=true` path runs Flyway directly and exits before Spring, HTTP,
   Kafka, schedulers, or provider clients start.
6. Inspect the migration log, verify `flyway_schema_history`, and only then set
   `deploy_application_services = true` and apply the runtime `container_image_tag`.

The following outline intentionally uses placeholders. It demonstrates safe data flow but is not
authorization to create resources. `set +x` is mandatory; the values exist only in process memory:

```bash
set +x
cd deploy/terraform/aws

endpoint=$(terraform output -raw rds_endpoint)
database=$(terraform output -raw rds_database_name)
admin_username=$(terraform output -raw rds_bootstrap_username)
master_secret_arn=$(terraform output -raw rds_secret_arn)

master_json=$(aws secretsmanager get-secret-value \
  --secret-id "$master_secret_arn" --query SecretString --output text)
export PGPASSWORD=$(jq -r '.password' <<<"$master_json")
unset master_json

export LEDGERFLOW_MIGRATION_DB_PASSWORD=$(openssl rand -base64 48)
export LEDGERFLOW_API_DB_PASSWORD=$(openssl rand -base64 48)
export LEDGERFLOW_WORKER_DB_PASSWORD=$(openssl rand -base64 48)

PGSSLMODE=require psql \
  --host "${endpoint%:*}" --port "${endpoint##*:}" \
  --username "$admin_username" --dbname "$database" \
  --file sql/bootstrap-database-identities.sql

jq -n --arg username ledgerflow_migrator \
  --arg password "$LEDGERFLOW_MIGRATION_DB_PASSWORD" \
  '{username:$username,password:$password}' |
  aws secretsmanager put-secret-value \
    --secret-id "$(terraform output -raw migration_database_secret_arn)" \
    --secret-string file:///dev/stdin >/dev/null

jq -n --arg username ledgerflow_api --arg password "$LEDGERFLOW_API_DB_PASSWORD" \
  '{username:$username,password:$password}' |
  aws secretsmanager put-secret-value \
    --secret-id "$(terraform output -raw api_database_secret_arn)" \
    --secret-string file:///dev/stdin >/dev/null

jq -n --arg username ledgerflow_worker --arg password "$LEDGERFLOW_WORKER_DB_PASSWORD" \
  '{username:$username,password:$password}' |
  aws secretsmanager put-secret-value \
    --secret-id "$(terraform output -raw worker_database_secret_arn)" \
    --secret-string file:///dev/stdin >/dev/null

unset PGPASSWORD LEDGERFLOW_MIGRATION_DB_PASSWORD LEDGERFLOW_API_DB_PASSWORD \
  LEDGERFLOW_WORKER_DB_PASSWORD
```

Run the migration task with the output task definition, migration security group, and both private
application subnets. Capture the task ARN, wait, and fail the deployment unless the essential
container reports exit code zero:

```bash
task_arn=$(aws ecs run-task \
  --cluster "$(terraform output -raw ecs_cluster_name)" \
  --task-definition "$(terraform output -raw migration_task_definition_arn)" \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=$(terraform output -json private_application_subnet_ids),securityGroups=[$(terraform output -raw migration_security_group_id)],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)

aws ecs wait tasks-stopped \
  --cluster "$(terraform output -raw ecs_cluster_name)" --tasks "$task_arn"

test "$(aws ecs describe-tasks \
  --cluster "$(terraform output -raw ecs_cluster_name)" --tasks "$task_arn" \
  --query 'tasks[0].containers[?essential].exitCode | [0]' --output text)" = 0
```

Production deployment automation should build the network-configuration JSON structurally rather
than relying on shell interpolation. The outline is for operator comprehension; this repository
does not claim the never-run AWS command is execution evidence.

## Subsequent forward migrations

`migration_image_tag` and `container_image_tag` are separate on purpose:

1. Keep the runtime tag on the currently running version and update only `migration_image_tag` to
   the candidate immutable image tag.
2. Apply/register the migration task definition, run it, wait, and require exit code zero.
3. Verify schema history and application/schema backward compatibility.
4. Update `container_image_tag` to the already-migrated candidate and roll API/worker tasks.

Migrations must follow expand/contract compatibility when old and new tasks can overlap. A merged
Flyway migration is never edited or removed. A failed migration blocks rollout; it is repaired by
a new forward migration after reviewing PostgreSQL/Flyway state, never by forcing API/worker tasks
to start.

## Rotation

The three secret containers intentionally have no Terraform-managed secret versions: Terraform
state must not contain database passwords. Rotation is therefore a coordinated privileged
operation:

1. Generate a new value, rerun the bootstrap SQL with all three current/new values, and create a
   new Secrets Manager version.
2. Force a new deployment only for the affected long-running service; injected ECS secrets are
   read at task start and do not hot-reload.
3. Verify new connections, revoke the prior secret version according to the incident/change plan,
   and retain CloudTrail/PostgreSQL audit evidence without secret material.

Automatic rotation is not claimed. A database-aware, VPC-connected rotation Lambda or equivalent
workflow must update PostgreSQL and Secrets Manager atomically enough for bounded connection-pool
turnover, then be failure-tested before production. This is tracked as `LF-EXT-R009`; the exact
Checkov exceptions on the three secret containers reference that record and do not affect
repository-secret, application-artifact, or image vulnerability gates.

## Recovery and rollback

- If role bootstrap succeeds but secret publication fails, generate new values and rerun the
  idempotent bootstrap; never recover values from logs or state.
- If migration fails, leave application services at their prior version/count and inspect the
  one-shot task plus `flyway_schema_history`. Do not delete or edit a merged migration.
- Provider/application image rollback is allowed only when the migrated schema is explicitly
  backward compatible. Database rollback means a reviewed forward repair or restore to a new RDS
  instance under an approved data-recovery decision, never ad hoc down-migration.
- A stale migration task cannot affect ECS services directly: its task role is empty, its execution
  role can read only the migration secret, and its security group can reach only RDS plus required
  image/log/secret endpoints.

## Validation boundary

Run `make aws-database-identity-check` (or the underlying
`scripts/validate-aws-database-identities`) from the repository root. It packages the actual
application, creates disposable PostgreSQL 18 credentials, executes this bootstrap SQL, runs the
packaged migration-only entry path twice, checks all nine Flyway rows, verifies expected DML grants,
and proves API DDL and worker `TRUNCATE` are denied. CI runs the same command after the complete
Gradle verification lifecycle.

Terraform, TFLint, Checkov, and Trivy validate the static design. They do not prove RDS role
creation, ECS task launch, secret rotation, or rollout ordering against AWS because this repository
never runs `terraform apply`. Those remain mandatory pre-production execution evidence.
