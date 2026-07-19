# Final Residual Risks (Portfolio Release)

This is the single entry point for "what is genuinely not proven here" across the whole
repository — the MVP and all six portfolio extensions. It does not duplicate the detailed
registers below; it points to them and adds the risks specific to the six extensions, which
predate this document and therefore aren't in the MVP-scoped register.

- Review date: 2026-07-19
- Owner: LedgerFlow maintainer unless reassigned
- Scope: the full `v1.1.0-portfolio` release (MVP + Milestones 1-6)

No item below, in this document or the registers it points to, is accepted for a production
deployment. This is a portfolio/interview demonstration; see
[operational limitations](operational-limitations.md) for what "release" means here.

## MVP-scope risks (pre-existing, unchanged by the extensions)

The full severity-ranked register is [`docs/security/mvp-residual-risk-register.md`](security/mvp-residual-risk-register.md)
(10 entries, `LF-MVP-R001`-`R010`) and the container-image exceptions are
[`docs/security/local-development-container-risk-register.md`](security/local-development-container-risk-register.md).
Neither register changed as part of Milestones 1-6 — none of the six extensions touch the
application's business logic, its data, or its threat model. Re-reading both in full is the
right move before treating this repository as more than a demonstration.

## Extension-scope risks (Milestones 1-6)

| ID | Severity | Residual risk | Existing mitigation and evidence | Trigger for re-review |
| --- | --- | --- | --- | --- |
| LF-EXT-R001 | Medium | `scripts/security-scan` (Docker-socket-privileged, Trivy secret + misconfig scan) never runs on a pull request by design — only on push-to-`main` and on schedule, so a malicious or careless PR from a fork cannot exfiltrate through Docker-socket access. This means a PR can pass CI green without that specific gate having run against it at all. | `ci.yml`/`codeql.yml` do run on every PR (build, tests, static analysis, image build + Trivy image scan); `security-scan.yml`'s trigger choice and rationale are documented in `README.md`'s Continuous integration section. | Before ever accepting external contributions, not just maintainer-authored branches. |
| LF-EXT-R002 | Medium | The AWS Terraform design (Milestone 5) is validated but **never applied** — `terraform plan`/`apply` never ran against real AWS APIs in this sandbox (no credentials). Static validation (`fmt`/`validate`/`tflint`/`checkov`/Trivy) already missed three real runtime defects that only manual review caught (see `docs/aws-terraform-design.md`, "A note on what 'validated' does and doesn't mean here"); a real `apply` could surface further defects none of the five tools or that review catches. | Three found-and-fixed defects documented with root cause; the note above states this limitation explicitly rather than implying `plan`/`apply`-equivalent confidence. | Before ever running `terraform apply` against a real account: budget a `plan` review pass and expect to find at least one more issue static tools can't see. |
| LF-EXT-R003 | Low | The Terraform cost estimate (~$232/month baseline) is a manually computed unit-price x quantity table, not a live `infracost` or AWS Pricing API quote — `docs/aws-terraform-design.md`'s own "Cost estimate" section states this and links the AWS Pricing Calculator as the thing to check before budgeting for real. | Every row states its assumption so it can be checked independently; explicitly excludes data transfer and peak-autoscaling cost. | Before using this number for an actual budget decision. |
| LF-EXT-R004 | Medium | The AI assistant's sanitizer (`ai-assistant/src/ai_assistant/sanitizer.py`) only redacts secret shapes it recognizes (bearer/JWT/vendor-key-prefix/`key=value`/URL-userinfo patterns) — an arbitrary internal token format, a secret split across lines, or an unrecognized shape is not caught. Documented as a stated limitation, not implied away, in both the module's own docstring and `docs/ai-operations-assistant.md`, "What the sanitizer does and doesn't catch". | 11 tests exercise every recognized shape with runtime-constructed fake secrets; regex-based redaction is a real, useful mitigation for the common leak shapes, verified, not a completeness proof. | Before routing any telemetry source with an unreviewed or unusual secret format through this assistant. |
| LF-EXT-R005 | Medium | Prompt-injection resistance for the optional OpenAI provider is proven **structurally** (untrusted content is isolated to a delimited prompt section; tested directly) but not **behaviorally** — no automated test in this milestone calls a live model, so nothing proves a live model always honors the system prompt's instructions under adversarial input. `docs/ai-operations-assistant.md`, "Prompt-injection resistance" and "Evaluation fixtures" state this distinction explicitly, including why the eval-fixture injection checks against the deterministic fake provider are close to vacuous on their own. | 7 structural prompt-construction tests; citation grounding (`_ground_citations`) never trusts a model's self-reported citation regardless of what it claims. | Before relying on the real provider's output without a human reviewing it — this assistant is advisory-only and has no remediation capability regardless. |
| LF-EXT-R006 | Low | The OpenAI Responses API integration has been verified against a mocked HTTP transport (real request-building code, real SDK response-model shapes) but, as of this document's writing, has not yet been exercised against the live OpenAI API end to end — see the portfolio-extension-execplan's Milestone 6 "Outcome and follow-up" for status. | Structured-output schema, field names, and pricing were verified against live OpenAI documentation and the installed SDK's own Pydantic models on 2026-07-19, not assumed. | Update this row once the live smoke test (tracked separately) runs; a genuinely new residual item if that call reveals a schema/parameter mismatch the mocked test couldn't catch. |
| LF-EXT-R007 | Low | The local `kind` Kubernetes deployment (Milestone 4) and the AWS ECS Fargate Terraform design (Milestone 5) are two different, independently validated compute topologies for the same application — neither has been validated as a migration path from the other, and no real managed Kubernetes service (EKS/GKE/AKS) has been exercised. | Both are documented as what they are: a local `kind` proof (`docs/kubernetes-deployment.md`) and a never-applied Terraform design (`docs/aws-terraform-design.md`), not presented as interchangeable or as a documented migration. | Before treating either as the "real" target topology without deciding which one a production deployment would actually use. |

## What this document deliberately doesn't do

It doesn't restate every caveat already recorded in `docs/aws-terraform-design.md`,
`docs/ai-operations-assistant.md`, `docs/kubernetes-deployment.md`, or
`docs/container-hardening.md` — each of those documents' own "out of scope" / "what this does
and doesn't catch" / "note on what validated does and doesn't mean" sections remain the
authoritative, most detailed source for their own milestone. This document exists to make sure
none of those caveats are only discoverable by reading six separate files start to finish.
