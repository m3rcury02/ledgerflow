# Final Residual Risks (Portfolio Release)

This is the single entry point for "what is genuinely not proven here" across the whole
repository: the MVP and all seven portfolio extensions. It does not duplicate the detailed
registers below; it points to them and adds the risks specific to the seven extensions, which
predate this document and therefore aren't in the MVP-scoped register.

- Review date: 2026-07-22
- Owner: LedgerFlow maintainer unless reassigned
- Scope: the full `v1.1.0-portfolio` release (MVP + Milestones 1-7) and the post-tag maintenance changes on `main`

No item below, in this document or the registers it points to, is accepted for a production
deployment. This is a portfolio/interview demonstration; see
[operational limitations](operational-limitations.md) for what "release" means here.

## MVP-scope risks (pre-existing, unchanged by the extensions)

The full severity-ranked register is [`docs/security/mvp-residual-risk-register.md`](security/mvp-residual-risk-register.md)
(10 entries, `LF-MVP-R001`-`R010`) and the container-image exceptions are
[`docs/security/local-development-container-risk-register.md`](security/local-development-container-risk-register.md).
Neither register changed as part of Milestones 1-7. None of the seven extensions touch the
application's business logic, its data, or its threat model. Re-reading both in full is the
right move before treating this repository as more than a demonstration. Post-tag maintenance on
2026-07-22 refreshed both registers for new scanner intelligence without rewriting the completed
extension history.

## Extension-scope risks (Milestones 1-7)

| ID | Severity | Residual risk | Existing mitigation and evidence | Trigger for re-review |
| --- | --- | --- | --- | --- |
| LF-EXT-R001 | Medium | `scripts/security-scan` (Docker-socket-privileged, Trivy secret + misconfig scan) never runs on a pull request by design: only on push-to-`main` and on schedule, so a malicious or careless PR from a fork cannot exfiltrate through Docker-socket access. This means a PR can pass CI green without that specific gate having run against it at all. | `ci.yml`/`codeql.yml` do run on every PR (build, tests, static analysis, image build + Trivy image scan); `security-scan.yml`'s trigger choice and rationale are documented in `README.md`'s Continuous integration section. | Before ever accepting external contributions, not just maintainer-authored branches. |
| LF-EXT-R002 | Medium | The AWS Terraform design is validated but **never applied**: `terraform plan`/`apply` never ran against real AWS APIs because no AWS credentials were used. Static validation (`fmt`/`validate`/TFLint/Checkov/Trivy) already missed four real runtime defects that only manual review caught (see `docs/aws-terraform-design.md`, "A note on what 'validated' does and doesn't mean here"); a real `apply` could surface further defects none of the five tools or that review catches. | Four found-and-fixed defects are documented with root cause; the database identity/privilege path also has a disposable PostgreSQL 18 rehearsal. The design still states this limitation explicitly rather than implying `plan`/`apply`-equivalent confidence. | Before ever running `terraform apply` against a real account: budget a `plan` review pass and expect to find at least one more issue static tools cannot see. |
| LF-EXT-R003 | Low | The Terraform cost estimate (~$233/month baseline) is a manually computed unit-price x quantity table, not a live `infracost` or AWS Pricing API quote. `docs/aws-terraform-design.md`'s own "Cost estimate" section states this and links the AWS Pricing Calculator as the thing to check before budgeting for real. | Every row states its assumption so it can be checked independently; explicitly excludes data transfer and peak-autoscaling cost. | Before using this number for an actual budget decision. |
| LF-EXT-R004 | Medium | The AI assistant's sanitizer (`ai-assistant/src/ai_assistant/sanitizer.py`) only redacts secret shapes it recognizes (bearer/JWT/vendor-key-prefix/`key=value`/URL-userinfo patterns). An arbitrary internal token format, a secret split across lines, or an unrecognized shape is not caught. Documented as a stated limitation, not implied away, in both the module's own docstring and `docs/ai-operations-assistant.md`, "What the sanitizer does and doesn't catch". | 11 tests exercise every recognized shape with runtime-constructed fake secrets; regex-based redaction is a real, useful mitigation for the common leak shapes, verified, not a completeness proof. | Before routing any telemetry source with an unreviewed or unusual secret format through this assistant. |
| LF-EXT-R005 | Medium | Prompt-injection resistance for the optional OpenAI provider is proven **structurally** (untrusted content is isolated to a delimited prompt section; tested directly) but not **behaviorally**: no automated test in this milestone calls a live model, so nothing proves a live model always honors the system prompt's instructions under adversarial input. `docs/ai-operations-assistant.md`, "Prompt-injection resistance" and "Evaluation fixtures" state this distinction explicitly, including why the eval-fixture injection checks against the deterministic fake provider are close to vacuous on their own. | 7 structural prompt-construction tests; citation grounding (`_ground_citations`) never trusts a model's self-reported citation regardless of what it claims. | Before relying on the real provider's output without a human reviewing it; this assistant is advisory-only and has no remediation capability regardless. |
| LF-EXT-R006 | Low | The OpenAI Responses API integration has been verified against a mocked HTTP transport (real request-building code, real SDK response-model shapes) but, as of this document's writing, has not yet been exercised against the live OpenAI API end to end. | Structured-output schema, field names, and pricing were verified against live OpenAI documentation and the installed SDK's own Pydantic models on 2026-07-19, not assumed. | Update this row once the live smoke test runs; record a genuinely new residual item if that call reveals a schema or parameter mismatch the mocked test could not catch. |
| LF-EXT-R007 | Low | The local `kind` Kubernetes deployment (Milestone 4) and the AWS ECS Fargate Terraform design (Milestone 5) are two different, independently validated compute topologies for the same application. Neither has been validated as a migration path from the other, and no real managed Kubernetes service (EKS/GKE/AKS) has been exercised. | Both are documented as what they are: a local `kind` proof (`docs/kubernetes-deployment.md`) and a never-applied Terraform design (`docs/aws-terraform-design.md`), not presented as interchangeable or as a documented migration. | Before treating either as the "real" target topology without deciding which one a production deployment would actually use. |
| LF-EXT-R008 | Closed (was High) | The original never-applied ECS design gave both API and worker tasks the RDS-managed master credential and allowed long-running services to run Flyway. | Closed 2026-07-22: the master secret is now bootstrap-only; migration, API, and worker have distinct PostgreSQL roles, secret containers, IAM paths, and network identities; runtime Flyway is disabled; initial services fail closed at zero desired count; a one-shot task uses a separate immutable migration image tag. `scripts/validate-aws-database-identities` applies all nine migrations twice on PostgreSQL 18 and proves runtime DDL/`TRUNCATE` denial. The AWS path remains never-applied under `LF-EXT-R002`. | Reopen if a long-running task references the master/migration secret, gains schema ownership/DDL/destructive privileges, or enables Flyway. |
| LF-EXT-R009 | Medium | API, worker, and migration use distinct password-based database identities rather than RDS IAM authentication, and their three application secrets do not have automatic rotation. Correct rotation must change PostgreSQL and Secrets Manager coherently and account for ECS connection-pool turnover; merely enabling IAM authentication without implementing token generation/refresh, or adding a nominal secret schedule without a database-aware workflow, would be misleading. | Secret values never enter Terraform state; the exact `CKV_AWS_161` and three `CKV2_AWS_57` skips reference this record; [`docs/aws-database-identity-runbook.md`](aws-database-identity-runbook.md) defines bounded, coordinated manual rotation and service-specific restart. This is not accepted as production-ready automation. | Before any real AWS deployment: choose and failure-test either IAM token generation/pool refresh or a VPC-connected password-rotation workflow, including partial-failure recovery, old-version revocation, and connection-pool turnover. |

## What this document deliberately doesn't do

It doesn't restate every caveat already recorded in `docs/aws-terraform-design.md`,
`docs/ai-operations-assistant.md`, `docs/kubernetes-deployment.md`, or
`docs/container-hardening.md`. Each of those documents' own "out of scope" / "what this does
and doesn't catch" / "note on what validated does and doesn't mean" sections remains the
authoritative, most detailed source for their own milestone. This document exists to make sure
none of those caveats are only discoverable by reading several separate files start to finish.
