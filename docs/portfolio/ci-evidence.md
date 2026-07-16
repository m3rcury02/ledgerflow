# CI Evidence

Continuous Integration in LedgerFlow is strictly enforced through local pipeline-equivalent scripts and standard CI mechanisms.

## Build and Test Passing

The complete build lifecycle via `./gradlew clean verify` runs:
1. Formatting checks (Spotless)
2. Static analysis
3. Unit tests
4. Integration tests (PostgreSQL via Testcontainers)
5. Architecture boundary tests (Spring Modulith + ArchUnit)
6. OpenAPI contract validation

**Latest Result**: `BUILD SUCCESSFUL` (Verified on 2026-07-16)

## Security Scan Passes

The `./scripts/security-scan` script checks for:
- Repository secrets (fail on any)
- Application artifact vulnerabilities
- Docker Compose image vulnerabilities against a strict container risk register.

**Latest Result**: Security scan completed: secret and application gates passed; Compose findings are absent or exactly covered by unexpired local-development risk records. (Verified on 2026-07-16)
