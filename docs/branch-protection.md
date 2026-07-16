# Branch Protection and Release Governance

The `main` branch of LedgerFlow is intended to be strongly protected. We recommend configuring the following GitHub repository rules:

1. **Require a pull request before merging:**
   - Approvals required: At least 1.
   - Dismiss stale pull request approvals when new commits are pushed.
   - Require review from Code Owners (if configured).

2. **Require status checks to pass before merging:**
   - Require branches to be up to date before merging.
   - Required checks:
     - `Verify` (Build and Verify workflow)
     - `Analyze (java)` (CodeQL Analysis workflow)

3. **Require signed commits:**
   - Enforce commit signing to ensure non-repudiation and developer authenticity.

4. **Do not allow bypassing the above settings:**
   - Ensure even repository administrators are subject to the branch protection constraints.

5. **Linear history:**
   - Require linear history (Squash or Rebase merges only) to avoid messy merge commits.

These settings guarantee that no code enters the repository without successfully passing static analysis, security scanning (Trivy and CodeQL), unit tests, PostgreSQL and Kafka Testcontainers tests, architectural constraint validation, and peer review.
