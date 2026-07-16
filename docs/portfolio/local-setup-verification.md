# Local Setup Verification

The LedgerFlow local environment relies on Docker Compose and the Gradle Wrapper to ensure strict consistency across development and CI.

## Steps for Verification

1. **Verify Gradle & Dependencies**:
   ```bash
   ./gradlew --version
   ./gradlew clean verify
   ```
   *Expected Result*: A clean build with 100% tests passing, indicating that Java 25, Modulith rules, ArchUnit, and Testcontainers are correctly integrated.

2. **Verify Infrastructure Health**:
   ```bash
   ./scripts/dev-up
   ./scripts/dev-status
   ```
   *Expected Result*: All 9 containers (PostgreSQL, Kafka, Valkey, Keycloak, OpenTelemetry Collector, Prometheus, Grafana, Tempo, Loki) transition to the `healthy` state.

3. **Verify Security Scanning**:
   ```bash
   ./scripts/security-scan
   ```
   *Expected Result*: Passes without failing the build. Shows that no secrets are committed and Compose findings match unexpired risk records.

4. **Verify Application Boot**:
   ```bash
   export LEDGERFLOW_DB_URL=jdbc:postgresql://localhost:5432/ledgerflow
   export LEDGERFLOW_DB_USERNAME=ledgerflow
   export LEDGERFLOW_DB_PASSWORD='<local-password>'
   ./gradlew :application:bootRun
   ```
   *Expected Result*: The application starts without errors, runs Flyway migrations successfully up to the latest version, and serves metrics on port `8082`.
