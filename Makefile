.DEFAULT_GOAL := help

.PHONY: help run dev-up dev-down dev-reset dev-status smoke-test demo-mvp replay-dead-letter security-scan image-scan observability-check demo-observability format check-format static-analysis test integration-test architecture-test openapi-check compose-check docs-check verify clean

help:
	@echo "LedgerFlow developer commands"
	@echo "  make run                Run the application (requires PostgreSQL configuration)"
	@echo "  make dev-up             Start local dependencies and wait for health"
	@echo "  make dev-down           Stop local dependencies and preserve data"
	@echo "  make dev-reset          Delete local data and recreate dependencies"
	@echo "  make dev-status         Show local dependency health"
	@echo "  make smoke-test         Prove one complete MVP order journey"
	@echo "  make demo-mvp           Run the focused MVP scenario demonstration"
	@echo "  make replay-dead-letter Replay one validated DLT record with audit evidence"
	@echo "  make security-scan      Scan secrets, dependencies, and Compose images"
	@echo "  make image-scan         Build the app image and generate its SBOM + vulnerability scan"
	@echo "  make observability-check Validate metrics, traces, logs, rules, and dashboards"
	@echo "  make demo-observability Create one order and verify its Tempo/Loki trace"
	@echo "  make format             Apply formatting"
	@echo "  make check-format       Check formatting"
	@echo "  make static-analysis    Run compiler lint and Checkstyle"
	@echo "  make test               Run unit tests"
	@echo "  make integration-test   Run PostgreSQL Testcontainers tests"
	@echo "  make architecture-test  Run Spring Modulith and ArchUnit checks"
	@echo "  make openapi-check      Validate OpenAPI"
	@echo "  make compose-check      Validate Docker Compose configuration"
	@echo "  make docs-check         Validate documentation"
	@echo "  make verify             Run the complete verification lifecycle"

run:
	./gradlew :application:bootRun

dev-up:
	./scripts/dev-up

dev-down:
	./scripts/dev-down

dev-reset:
	./scripts/dev-reset

dev-status:
	./scripts/dev-status

smoke-test:
	./scripts/smoke-test

demo-mvp:
	./scripts/demo-mvp

replay-dead-letter:
	./scripts/replay-dead-letter "$(DLT_ID)" "$(RETRY_KEY)" "$(REASON)"

security-scan:
	./scripts/security-scan

image-scan:
	./scripts/scan-image

observability-check:
	./scripts/validate-observability

demo-observability:
	./scripts/demo-observability

format:
	./gradlew spotlessApply

check-format:
	./gradlew spotlessCheck

static-analysis:
	./gradlew staticAnalysis

test:
	./gradlew test

integration-test:
	./gradlew integrationTest

architecture-test:
	./gradlew architectureTest

openapi-check:
	./gradlew openApiValidate

compose-check:
	./gradlew composeValidate

docs-check:
	./gradlew documentationCheck

verify:
	./gradlew clean verify

clean:
	./gradlew clean
