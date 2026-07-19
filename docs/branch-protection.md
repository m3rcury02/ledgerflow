# Branch Protection Recommendations

These are recommendations for the repository owner to apply manually in GitHub
(**Settings → Branches → Branch protection rules**). Nothing in this repository applies
them automatically: repository and organization settings are not something an automated
change should alter without a human reviewing the consequences first.

## Recommended rule for `main`

- Require a pull request before merging. Require at least 1 approving review (solo
  maintainer: this can be satisfied by a second reviewing account, or waived explicitly —
  but the rule should exist so it is one settings change, not a habit, to relax it).
- Dismiss stale approvals when new commits are pushed.
- Require status checks to pass before merging, specifically:
  - `Verify` (from `.github/workflows/ci.yml`)
  - `Build and scan OCI image` (from `.github/workflows/ci.yml`)
  - `Analyze (java-kotlin)` (from `.github/workflows/codeql.yml`)
- Require branches to be up to date before merging.
- Require conversation resolution before merging.
- Do not allow force pushes to `main`.
- Do not allow deletion of `main`.
- Restrict who can push directly to `main` (require all changes through a reviewed pull
  request, including for administrators if the team is comfortable with that friction).

## Recommended repository-level settings

- Enable GitHub secret scanning and push protection (**Settings → Code security**). This is
  complementary to, not a replacement for, the repository-content Trivy secret scan already
  run by `scripts/security-scan` and `.github/workflows/security-scan.yml` — GitHub's
  scanner covers the push path itself, including branches this repository's own local
  tooling never runs against.
- Enable Dependabot security updates in addition to the version-update configuration in
  `.github/dependabot.yml`.
- Require signed commits, if the maintainer's workflow supports it. Not required for this
  portfolio project, but worth noting as a stronger option for a production repository.

## Why these are documented, not applied

`gh api` and the GitHub REST/GraphQL APIs can apply every rule above non-interactively.
This repository's automation intentionally does not do that: branch protection and
repository security settings are consequential, hard-to-review-at-a-glance changes to
shared infrastructure, and the extension plan that introduced this document
(`docs/plans/portfolio-extension-execplan.md`) explicitly excludes destructive or
irreversible repository configuration changes from automated execution.
