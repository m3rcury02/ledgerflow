# Container Evidence

LedgerFlow uses a production-hardened Docker container based on the Java 25 runtime.

## Hardening Features

- **Multi-stage Build:** The application is built using the Gradle Wrapper in a builder stage (using `eclipse-temurin:25-jdk`) and packaged into a minimal runtime stage (using `eclipse-temurin:25-jre-alpine`).
- **Non-Root Execution:** A dedicated `ledgerflow` user and group are created, and the image runs entirely as this unprivileged user.
- **Read-Only Root Filesystem Compatibility:** The container is compatible with a read-only root filesystem. The only writable volume required is `/tmp`, which is mounted as a temporary directory.
- **Container-Aware JVM Defaults:** Java options such as `-XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=75.0` are configured.
- **Graceful Shutdown:** The container entrypoint uses the `exec` form to properly forward `SIGTERM` signals to the JVM for graceful application shutdown.
- **Vulnerability Scanning:** The image is scanned against the Trivy vulnerability database during the CI build process.
- **OCI Labels:** Standard OCI metadata annotations are included.

## Validation

The container has been successfully validated by:
1. Building the `ledgerflow:latest` image.
2. Confirming it runs as the non-root `ledgerflow` user.
3. Starting it with the `--read-only` flag.
4. Connecting it to PostgreSQL and Kafka Testcontainers.
5. Verifying the `/actuator/health` endpoint responds correctly.
6. Gracefully stopping the container and verifying successful shutdown.
7. Scanning the image for vulnerabilities with Trivy.
