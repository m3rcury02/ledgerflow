# AWS Terraform Design (Milestone 5)

This document records Milestone 5 (AWS Terraform design) of
`docs/plans/portfolio-extension-execplan.md`: a validated, **never-applied** reference
architecture under `deploy/terraform/aws/`. Every validation claim below was checked with a
real command run on 2026-07-19 - `terraform fmt`/`validate`, `tflint`, and Checkov all pass
cleanly, and the repository's existing Trivy-based `scripts/security-scan` gate was extended
to scan this Terraform too (not skip-listed, unlike Milestone 4's vendored `kind` dependency
manifests - see "Validation" below). No `terraform plan`, no `terraform apply`, no cloud
resources, and no real AWS account IDs are involved anywhere in this milestone.

## Architecture

Two-AZ VPC, three subnet tiers, no NAT Gateway:

| Tier | Subnets | Contents |
| --- | --- | --- |
| Public | 2 (one per AZ) | Application Load Balancer only |
| Private application | 2 (one per AZ) | ECS Fargate tasks (api, worker) |
| Private data | 2 (one per AZ) | RDS PostgreSQL only - no route beyond the VPC at all |

```
Internet
   |
   v
[ALB] (public subnets, 2 AZ)
   |  :8080
   v
[ECS Fargate: ledgerflow-api]  (private-app subnets, 2-5 tasks, Application Auto Scaling)
   |                                        [ECS Fargate: ledgerflow-worker] (private-app, fixed 2 tasks,
   |  :5432                                  no ALB target, no ingress rule of any kind)
   v                                              |  :5432
[RDS PostgreSQL, Multi-AZ] (private-data subnets, no route to the internet)  <----------------+

ECR, CloudWatch Logs, Secrets Manager reached via VPC interface endpoints (private-app route
table); ECR image layers via an S3 gateway endpoint. No NAT Gateway anywhere in this design.
```

Same api/worker split Milestone 4's Helm chart already validated against a real cluster (see
`docs/kubernetes-deployment.md`): one image, two ECS services distinguished only by the four
`LEDGERFLOW_*_ENABLED` background-job flags. `api` is purely request/response and the only
service behind the ALB; `worker` is purely background processing, fixed at 2 tasks, and has
no ALB target group, no Application Auto Scaling target, and no security-group ingress rule
of any kind - the same "no rule permits inbound traffic at all" property Milestone 4's worker
`NetworkPolicy` enforced, realized here at the security-group layer instead.

## Design decisions

- **VPC interface/gateway endpoints instead of a NAT Gateway.** ECS tasks reach ECR
  (`ecr.api`, `ecr.dkr`), CloudWatch Logs, and Secrets Manager through VPC interface
  endpoints, and ECR image layers through an S3 gateway endpoint (free, no ENIs) - never
  through a NAT Gateway. The private application and data subnets have no route to the
  internet at all, at any point: there is no egress path to remove later, only one to add if
  a future requirement genuinely needs it. This is the direct AWS analog of the least-privilege
  posture already established in Milestone 4 (zero-RBAC ServiceAccounts, deny-by-default
  NetworkPolicies).
- **Three subnet tiers, not two.** RDS lives in dedicated private-data subnets with their own
  route table (no NAT/endpoint route at all - RDS never needs to reach out), separate from the
  ECS private-application subnets. This is standard RDS subnet-group practice and costs two
  extra (free) subnets and one extra route table, not additional compute.
- **`ap-south-1` (Mumbai) as the default region.** LedgerFlow's documented functional scope is
  INR-only (`docs/operational-limitations.md`); a Mumbai-region default keeps the reference
  architecture consistent with the application's own currency scope, though `aws_region` is a
  variable and any region works.
- **Kafka and an identity provider are explicitly out of scope.** The extension prompt's
  service list for this milestone - VPC/ECS Fargate/RDS/ECR/Secrets Manager/CloudWatch - does
  not include a managed Kafka or identity-provider service, unlike Milestone 4, which deployed
  both Kafka and Keycloak in-cluster. Following the same reasoning Milestone 4 applied to
  excluding Valkey, this design's compute/data tier is a **partial** production topology: a
  real deployment would add Amazon MSK (or self-managed Kafka on ECS) and either a
  self-hosted Keycloak service or Amazon Cognito. `kafka_bootstrap_servers` and
  `oauth2_issuer_uri` are Terraform variables with placeholder defaults and an explicit
  comment, not fabricated infrastructure standing in for either.
- **RDS master credential is never a Terraform variable.** `manage_master_user_password = true`
  makes RDS itself generate, store, and rotate the master password in Secrets Manager - no
  password ever appears in a `.tf` file, a `terraform.tfvars`, or Terraform state as plaintext.
  The ECS task definitions reference the resulting secret ARN directly
  (`aws_db_instance.this.master_user_secret[0].secret_arn`).
- **Dedicated least-privilege IAM roles per service role, never shared.** Mirrors Milestone
  4's dedicated `ledgerflow-api`/`ledgerflow-worker` ServiceAccounts: each role's ECS
  execution role can reach only its own CloudWatch log group, the shared ECR repository, and
  the shared RDS secret; each role's ECS task role is empty, because the application itself
  never calls an AWS API.
- **Application Auto Scaling on the api service, not the worker service.** Target-tracking
  CPU scaling at 50%, min 2 / max 5 - the exact values Milestone 4's Helm chart HPA already
  validated with a real load burst (`docs/kubernetes-deployment.md`: 2->4 replicas in 27s at
  89% CPU). The worker service has a fixed task count, matching the same "background
  processing shouldn't autoscale against shared outbox/notification/recovery tables"
  reasoning Milestone 4 recorded in its Decision log.
- **One shared customer-managed KMS key, not one per resource.** CloudWatch Logs and the ECR
  repository share a single CMK. A key per resource would be more granular but disproportionate
  to a portfolio-scale design; one shared key is still a real, audited, rotating
  customer-managed key rather than accepting AWS-owned/AWS-managed key defaults everywhere.
- **AWS WAFv2 and ALB access logging (to S3) are out of scope.** Both are real, common
  production additions, but both introduce a materially new AWS service/resource surface not
  in this milestone's literal service list (WAF is its own service with managed rule groups;
  ALB access logs need a dedicated S3 log-delivery bucket, and S3 is not on the list either).
  Documented as accepted, explained gaps (see "Validation" below), not silently dropped.
- **HTTPS is conditional on `acm_certificate_arn`.** No real domain or ACM certificate exists
  for a never-applied design; the ALB serves HTTP directly by default and gains an HTTPS
  listener (with HTTP -> HTTPS redirect) the moment a real certificate ARN is supplied.

## Out of scope

- **Amazon MSK / Kafka** - see "Design decisions" above. `kafka_bootstrap_servers` is a
  placeholder variable.
- **Identity provider (Keycloak or Amazon Cognito)** - see "Design decisions" above.
  `oauth2_issuer_uri` is a placeholder variable.
- **AWS WAFv2** in front of the ALB.
- **ALB access logging to S3** (would require a dedicated log-delivery bucket).
- **Multi-region / cross-region replication** for the Terraform state bucket.

## A note on what "validated" does and doesn't mean here

`terraform validate`, `tflint`, and Checkov/Trivy all operate on the configuration statically
- none of them execute a plan against real AWS APIs, so none of them can catch a defect that
only manifests at `RegisterTaskDefinition` or service-stabilization time. Three such defects
were found by manual review (not by any tool, and not by applying, since this milestone never
applies) and fixed before this milestone was considered complete:

- **ALB health checks were blocked by security groups.** The api target group's health check
  correctly targets port `8081` (the management/Actuator port - `8080` only serves the
  application API, which has no unauthenticated health endpoint), but the original api
  security group only allowed ingress on `8080` from the ALB, and the ALB security group only
  allowed egress on `8080`. Targets would never have gone healthy and the api service's
  rollout would have hung indefinitely. Fixed by adding a dedicated `8081`
  ingress/egress rule pair, scoped only to ALB<->api - no public listener routes to `8081`, so
  this enables health checks without exposing the management port publicly.
- **`tmpfs` is not supported on Fargate.** The original api/worker container definitions used
  `linuxParameters.tmpfs` for a writable `/tmp` under `readonlyRootFilesystem: true` - a
  pattern that works on the EC2 launch type but that Fargate's `RegisterTaskDefinition`
  rejects outright. Fixed by switching to an ephemeral task-level `volume` block plus a
  `mountPoints` entry, the correct Fargate-native mechanism for the same property.
- **The RDS-managed secret was wired as a single opaque blob, and the wrong DB environment
  variables were used.** `manage_master_user_password` stores the credential as a JSON
  document (`{"username":...,"password":...,...}`), not a scalar string; the original
  `secrets` block injected the whole ARN into one `LEDGERFLOW_DB_CREDENTIALS` variable the
  application does not read. Separately, the application's actual datasource configuration
  (`application/src/main/resources/application.yaml`) takes a single `LEDGERFLOW_DB_URL`
  (a full JDBC URL) plus `LEDGERFLOW_DB_USERNAME`/`LEDGERFLOW_DB_PASSWORD` - not the
  `LEDGERFLOW_DB_HOST`/`_PORT`/`_NAME` triple this configuration originally invented without
  checking the real property names. Fixed by reading the actual `application.yaml` datasource
  block, constructing `LEDGERFLOW_DB_URL` from the real RDS attributes, and projecting the
  secret's two individual JSON keys via ECS's `secretArn:jsonKey::` syntax
  (`LEDGERFLOW_DB_USERNAME`, `LEDGERFLOW_DB_PASSWORD`) instead of the whole document.

None of these three findings changed a single `fmt`/`validate`/`tflint`/`checkov`/Trivy
result - all were, and remain, green throughout. That is the point being recorded here:
static validation is real evidence of syntactic correctness and scanner-level security
posture, not of runtime correctness, and a "validated, never-applied" design is only as
trustworthy as the review that goes beyond what the validators check.

## Remote state bootstrap

`deploy/terraform/aws/bootstrap/` is a separate, minimal root module that creates the S3
bucket and DynamoDB lock table the main configuration's own remote state depends on - it
cannot depend on the backend it creates, so it uses local state and is applied once, manually,
before the main configuration is ever initialized:

```bash
cd deploy/terraform/aws/bootstrap
terraform init
terraform apply -var state_bucket_name=<globally-unique-name>   # e.g. ledgerflow-tfstate-<your-account-id>
# note the state_bucket_name and lock_table_name outputs

cd ../
terraform init \
  -backend-config="bucket=<state_bucket_name output>" \
  -backend-config="key=ledgerflow/aws/terraform.tfstate" \
  -backend-config="region=<aws_region>" \
  -backend-config="dynamodb_table=<lock_table_name output>" \
  -backend-config="encrypt=true"
```

The bootstrap bucket is versioned, SSE-KMS-encrypted (AWS-managed key - see "Validation" for
why not a CMK here specifically), fully public-access-blocked, TLS-only via bucket policy, and
has a 90-day noncurrent-version expiration lifecycle rule. The lock table uses on-demand
billing, point-in-time recovery, and server-side encryption.

## Cost estimate

**No live pricing tool was available in this sandbox** (`infracost` is not installed, and
there is no network path to price HCL against a live AWS pricing API here). The table below is
a manually computed, auditable unit-price x quantity breakdown instead of a single memorized
number - every row states its assumption so it can be checked or refreshed independently.
Region: `ap-south-1`. Pricing basis: approximate AWS list pricing patterns, **not** a live
quote - verify with the [AWS Pricing Calculator](https://calculator.aws) or Cost Explorer
before budgeting a real deployment; AWS list prices change and regional prices differ from
the commonly-cited us-east-1 baseline this estimate is anchored to.

| Component | Assumption | Approx. monthly cost |
| --- | --- | --- |
| Application Load Balancer | 1 ALB, ~730 hrs, light-moderate LCU usage | ~$25 |
| VPC interface endpoints | 4 endpoints (ecr.api, ecr.dkr, logs, secretsmanager) x 2 AZ x ~$0.01/hr, plus light data processing | ~$60 |
| S3 gateway endpoint (ECR layers) | Gateway endpoints have no hourly charge | $0 |
| ECS Fargate (api) | 2 tasks baseline (0.5 vCPU / 1 GiB each); autoscales to 5 under load - baseline only, below | ~$49 |
| ECS Fargate (worker) | 2 fixed tasks (0.5 vCPU / 1 GiB each) | ~$49 |
| RDS `db.t4g.micro`, Multi-AZ | Instance + 20 GiB gp3 storage, both billed 2x under Multi-AZ | ~$38 |
| ECR storage | <1 GiB image | ~$1 |
| CloudWatch Logs | Light ingestion/storage at this traffic scale, 400-day retention | ~$8 |
| KMS | 1 customer-managed key (shared: logs + ECR) | ~$1 |
| Secrets Manager | 1 secret (RDS-managed master credential) | ~$0.40 |
| DynamoDB lock table + S3 state bucket (bootstrap) | On-demand billing, negligible size/request volume | ~$0.20 |
| **Baseline total** | | **~$232/month** |

Explicitly excluded: data transfer (workload-dependent, can dominate at real traffic volumes),
peak-load Fargate cost above the api service's 2-task baseline (autoscaling to 5 tasks under
sustained load adds up to ~3 more api tasks, ~$37/month more at the same per-task rate), and
any cost from Kafka/an identity provider (out of scope - see above).

**Note on VPC endpoints vs. NAT Gateway**: this design's endpoint-based approach is
cost-*comparable* to a single NAT Gateway (~$33/month base + ~$0.045/GB processed) and cheaper
than the two-NAT-Gateway topology a genuinely NAT-based two-AZ design would need for the same
AZ-independence (~$66/month base) - not dramatically cheaper in isolation. The real advantage
is categorical, not incremental: there is no internet egress path from the private subnets at
all, so there is nothing to misconfigure into an accidental exposure later.

## Validation

```bash
cd deploy/terraform/aws && terraform fmt -check -recursive .   # clean
cd deploy/terraform/aws && terraform init -backend=false && terraform validate
cd deploy/terraform/aws/bootstrap && terraform init -backend=false && terraform validate
```

Both modules: `Success! The configuration is valid.` `terraform plan`/`apply` are deliberately
never run (per this milestone's "never applied" constraint and the resulting lack of AWS
credentials in this sandbox) - `-backend=false` avoids the S3 backend even attempting to
initialize.

```bash
tflint --init   # installs the aws ruleset plugin
tflint          # both deploy/terraform/aws and deploy/terraform/aws/bootstrap
```

Zero issues in either module (the `aws` and `terraform` rulesets both enabled).

```bash
checkov -d deploy/terraform/aws --compact
```

`0` failed checks, `16` explicitly suppressed with an inline `#checkov:skip=<ID>:<rationale>`
comment (Checkov's Terraform skip comments must be placed **inside** the failing resource
block, not above it - confirmed empirically in this milestone; a comment placed before the
`resource` keyword is silently ignored). Every suppressed finding is a real, considered
trade-off, not a blanket exclusion - e.g. the ALB's internet-facing `0.0.0.0/0` ingress (it is
a public web application's entry point by design), the RDS-managed-password rotation check
(superseded by `manage_master_user_password`, a newer and stronger mechanism than the
customer-managed rotation Lambda the check expects), and the bootstrap module's DynamoDB/S3
customer-managed-key checks (a dedicated KMS key for a lock table and state bucket would be a
circular bootstrap dependency for a module whose entire purpose is being the minimal
prerequisite).

**Trivy also scans this Terraform**, via the repository's existing `scripts/security-scan`
gate (Trivy has its own, independent Terraform misconfig scanner, distinct rule IDs
`AVD-AWS-*`/`AWS-*`, separate from Checkov). Unlike Milestone 4's `deploy/kind/dependencies/`
(vendored, dev-only, verbatim-upstream manifests, which justified a directory skip), this
Terraform is hand-authored and meant to be exemplary, so the target here was passing Trivy
cleanly too, not adding another directory skip:

```bash
docker run --rm ... ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe... \
  config --misconfig-scanners terraform --severity HIGH,CRITICAL \
  --exit-code 1 /workspace
```

Found 3 findings on the first run, all already-considered trade-offs already suppressed for
Checkov under a different rule ID: the ALB's public exposure and HTTP listener (`AWS-0053`,
`AWS-0054` - same rationale as the corresponding Checkov skips), and the bootstrap S3 bucket's
AWS-managed (not customer-managed) KMS key (`AWS-0132` - same rationale as the DynamoDB
skip). Suppressed with matching `#trivy:ignore:<ID> <rationale>` comments referencing the
corresponding Checkov skip. (Trivy's ignore comments follow the *opposite* placement rule
from Checkov's: they must be **directly above** the resource/attribute line they suppress,
not inside the block - confirmed empirically, and recorded here since it is easy to get
backwards.)

Re-ran through the real repository gate afterward, not just the standalone probe above -
`./scripts/security-scan`'s existing `trivy fs --scanners secret,misconfig` invocation
already covers the whole repository, so **no script changes were needed** for this milestone
(unlike Milestone 4, which needed a `--skip-dirs` addition). Real output confirming both
Terraform roots were scanned and all three ignores were honored:

```
[terraform scanner] Scanning root module  file_path="deploy/terraform/aws"
[terraform scanner] Scanning root module  file_path="deploy/terraform/aws/bootstrap"
[terraform executor] Ignore finding  rule="aws-elb-http-not-used" range="deploy/terraform/aws/alb.tf:48-75"
[terraform executor] Ignore finding  rule="aws-elb-alb-not-public" range="deploy/terraform/aws/alb.tf:8"
[terraform executor] Ignore finding  rule="aws-s3-encryption-customer-key" range="deploy/terraform/aws/bootstrap/main.tf:55-64"
...
Security scan completed: secret and application gates passed; Compose findings are absent or
exactly covered by unexpired local-development risk records.
```

Full `./scripts/security-scan` run exited `0`. The only findings present anywhere in that run
are the pre-existing, already-accepted Keycloak/Valkey Compose image risk records Milestones
1-4 already recorded - nothing new or unaddressed from this milestone's changes.

Final combined state: `terraform fmt`/`validate` clean, `tflint` clean, Checkov `0` failed /
`16` suppressed-with-rationale, Trivy (via `scripts/security-scan`) `0` findings. No
`terraform plan`, `apply`, or cloud credentials were used at any point.

## Teardown instructions

This design is never applied in this milestone, but the instructions below are what a real
deployment's teardown would require, in order - included because "how do you tear this down"
is itself part of a credible design, not just "how do you stand it up":

1. **Disable deletion protection first.** Both the RDS instance and (if enabled) the ALB
   default to protected. `terraform apply -var db_deletion_protection=false` (a plan that
   changes only that one attribute) before attempting to destroy, or `terraform destroy` will
   fail on the RDS instance with an AWS API error, not a Terraform one.
2. **`terraform destroy`** in `deploy/terraform/aws/` (the main configuration). RDS takes a
   final snapshot by design (`skip_final_snapshot = false`) - this is intentional, not a bug
   to work around; the snapshot itself is **not** deleted by `terraform destroy` and must be
   removed separately if it is genuinely not wanted.
3. **Empty and destroy the state bucket, if actually decommissioning the environment for
   good.** `deploy/terraform/aws/bootstrap/`'s S3 bucket has `prevent_destroy = true` and (once
   real state objects exist in it) cannot be destroyed while non-empty. Remove the
   `prevent_destroy` lifecycle block, empty all object versions (`aws s3api
   delete-objects`/`aws s3 rm --recursive` is not sufficient by itself with versioning enabled
   - every version of every object must be deleted), then `terraform destroy` in `bootstrap/`.
   This is deliberately the **last** step: destroying it earlier would delete the very state
   file the main configuration's own `terraform destroy` in step 2 needs to know what to tear
   down.
4. **ECR repository images.** `aws_ecr_repository` does not `force_delete` by default in this
   configuration; if the repository still has images when `terraform destroy` reaches it, the
   destroy fails until they are removed (`aws ecr batch-delete-image` or `aws ecr
   delete-repository --force`, outside Terraform).

## Reproducing this milestone's evidence

```bash
cd deploy/terraform/aws && terraform fmt -check -recursive .
cd deploy/terraform/aws && terraform init -backend=false && terraform validate
cd deploy/terraform/aws/bootstrap && terraform init -backend=false && terraform validate
cd deploy/terraform/aws && tflint --init && tflint
cd deploy/terraform/aws/bootstrap && tflint --init && tflint
checkov -d deploy/terraform/aws --compact
./scripts/security-scan
```
