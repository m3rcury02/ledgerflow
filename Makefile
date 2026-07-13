.DEFAULT_GOAL := help

.PHONY: help run format check-format static-analysis test integration-test architecture-test openapi-check docs-check verify clean

help:
	@echo "LedgerFlow developer commands"
	@echo "  make run                Run the application (requires PostgreSQL configuration)"
	@echo "  make format             Apply formatting"
	@echo "  make check-format       Check formatting"
	@echo "  make static-analysis    Run compiler lint and Checkstyle"
	@echo "  make test               Run unit tests"
	@echo "  make integration-test   Run PostgreSQL Testcontainers tests"
	@echo "  make architecture-test  Run Spring Modulith and ArchUnit checks"
	@echo "  make openapi-check      Validate OpenAPI"
	@echo "  make docs-check         Validate documentation"
	@echo "  make verify             Run the complete verification lifecycle"

run:
	./gradlew :application:bootRun

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

docs-check:
	./gradlew documentationCheck

verify:
	./gradlew clean verify

clean:
	./gradlew clean
