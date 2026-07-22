import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    base
    id("org.springframework.boot") version "4.1.0" apply false
    id("com.diffplug.spotless") version "8.8.0"
}

group = "com.ledgerflow"
version = "0.1.0-SNAPSHOT"

val springBootVersion = "4.1.0"
val springModulithVersion = "2.1.0"
val resilience4jVersion = "2.4.0"
val openTelemetryInstrumentationVersion = "2.28.1-alpha"
// Spring Boot 4.1.0 manages 42.7.11, which is affected by CVE-2026-54291. Remove this narrow
// override once a compatible Boot BOM manages pgjdbc 42.7.12 or newer.
val postgresqlDriverVersion = "42.7.12"

extra["springBootVersion"] = springBootVersion
extra["springModulithVersion"] = springModulithVersion
extra["resilience4jVersion"] = resilience4jVersion
extra["openTelemetryInstrumentationVersion"] = openTelemetryInstrumentationVersion
extra["postgresqlDriverVersion"] = postgresqlDriverVersion

configure(subprojects.filter { it.path != ":modules" }) {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "java-library")
    apply(plugin = "checkstyle")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = "13.8.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxErrors = 0
        maxWarnings = 0
    }

    dependencies {
        add(
            "implementation",
            platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"),
        )
        add(
            "testImplementation",
            platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"),
        )
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("user.timezone", "UTC")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<Checkstyle>().configureEach {
        reports {
            xml.required = true
            html.required = true
        }
    }
}

extensions.configure<SpotlessExtension> {
    java {
        target("application/src/**/*.java", "modules/*/src/**/*.java")
        googleJavaFormat()
        formatAnnotations()
        removeUnusedImports()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
    format("repositoryText") {
        target(
            ".gitignore",
            ".env.example",
            "*.md",
            "docs/**/*.md",
            "**/*.properties",
            "**/*.xml",
            "**/*.yaml",
            "**/*.yml",
            "infra/**/*.json",
            "Makefile",
            "scripts/dev-*",
            "scripts/replay-*",
            "scripts/security-*",
            "scripts/validate-aws-database-identities",
            "scripts/*observability*",
            "scripts/smoke-test",
            "scripts/demo-mvp",
        )
        targetExclude("**/build/**", ".gradle/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val openApiValidator =
    configurations.create("openApiValidator") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    openApiValidator("org.openapitools:openapi-generator-cli:7.24.0")
}

val test =
    tasks.register("test") {
        group = "verification"
        description = "Runs unit tests in every subproject."
        subprojects.forEach { dependsOn(it.tasks.matching { task -> task.name == "test" }) }
    }

val integrationTest =
    tasks.register("integrationTest") {
        group = "verification"
        description = "Runs PostgreSQL Testcontainers integration tests."
        dependsOn(":application:integrationTest")
    }

val architectureTest =
    tasks.register("architectureTest") {
        group = "verification"
        description = "Runs Spring Modulith and ArchUnit architecture checks."
        dependsOn(":application:architectureTest")
    }

val forbiddenDependencyCheck =
    tasks.register("forbiddenDependencyCheck") {
        group = "verification"
        description = "Rejects dependencies prohibited by repository governance."
        val buildScripts =
            fileTree(rootDir) {
                include("**/*.gradle.kts")
                exclude("**/build/**")
            }
        inputs.files(buildScripts)
        doLast {
            val forbidden = Regex("com\\.h2database\\s*:\\s*h2", RegexOption.IGNORE_CASE)
            val offenders = buildScripts.files.filter { forbidden.containsMatchIn(it.readText()) }
            check(offenders.isEmpty()) {
                "H2 is prohibited; remove it from: ${offenders.joinToString { it.relativeTo(rootDir).path }}"
            }
        }
    }

val staticAnalysis =
    tasks.register("staticAnalysis") {
        group = "verification"
        description = "Runs Checkstyle, javac lint checks, and forbidden-dependency checks."
        dependsOn(forbiddenDependencyCheck)
        subprojects.forEach { project ->
            dependsOn(
                project.tasks.matching { task ->
                    task.name.startsWith("checkstyle") ||
                        (task.name.startsWith("compile") && task.name.endsWith("Java"))
                },
            )
        }
    }

val openApiValidate =
    tasks.register<JavaExec>("openApiValidate") {
        group = "verification"
        description = "Validates the version-controlled OpenAPI contract."
        val contract = layout.projectDirectory.file("application/src/main/openapi/ledgerflow.yaml")
        inputs.file(contract)
        classpath = openApiValidator
        mainClass = "org.openapitools.codegen.OpenAPIGenerator"
        args("validate", "--input-spec", contract.asFile.absolutePath)
    }

val mockProviderOpenApiValidate =
    tasks.register<JavaExec>("mockProviderOpenApiValidate") {
        group = "verification"
        description = "Validates the test-only mock payment-provider contract."
        val contract =
            layout.projectDirectory.file(
                "application/src/testFixtures/openapi/mock-payment-provider.yaml",
            )
        inputs.file(contract)
        classpath = openApiValidator
        mainClass = "org.openapitools.codegen.OpenAPIGenerator"
        args("validate", "--input-spec", contract.asFile.absolutePath)
    }

val composeValidate =
    tasks.register<Exec>("composeValidate") {
        group = "verification"
        description = "Validates the local Docker Compose environment."
        inputs.file(layout.projectDirectory.file("compose.yaml"))
        inputs.file(layout.projectDirectory.file(".env.example"))
        inputs.files(layout.projectDirectory.dir("infra"))
        commandLine(
            "docker",
            "compose",
            "--env-file",
            ".env.example",
            "--file",
            "compose.yaml",
            "config",
            "--quiet",
        )
    }

val documentationCheck =
    tasks.register("documentationCheck") {
        group = "verification"
        description = "Checks required documentation and local Markdown links."

        val requiredPaths =
            listOf(
                "README.md",
                "docs/architecture.md",
                "docs/development-workflow.md",
                "docs/definition-of-done.md",
                "docs/product-requirements.md",
                "docs/domain-model.md",
                "docs/api-design.md",
                "docs/data-model.md",
                "docs/threat-model.md",
                "docs/runbook.md",
                "docs/observability.md",
                "docs/observability-runbook.md",
                "docs/mvp-evidence.md",
                "docs/mvp-review.md",
                "docs/failure-injection.md",
                "docs/operational-limitations.md",
                "docs/dependency-inventory.md",
                "docs/migration-inventory.md",
                "docs/runbook-index.md",
                "docs/security/local-development-container-risk-register.md",
                "docs/security/mvp-residual-risk-register.md",
                "docs/sql/ledger-queries.sql",
                "docs/adr/0001-record-architecture-decisions.md",
                "docs/adr/README.md",
                "compose.yaml",
                ".env.example",
                "scripts/dev-up",
                "scripts/dev-down",
                "scripts/dev-reset",
                "scripts/dev-status",
                "scripts/replay-dead-letter",
                "scripts/security-scan",
                "scripts/validate-observability",
                "scripts/validate-aws-database-identities",
                "scripts/demo-observability",
                "scripts/smoke-test",
                "scripts/demo-mvp",
                "config/security/local-compose-vulnerability-exceptions.json",
            )
        val markdownFiles =
            fileTree(rootDir) {
                include("*.md", "docs/**/*.md")
                exclude("**/build/**")
            }
        inputs.files(markdownFiles)

        doLast {
            val missing = requiredPaths.filterNot { rootProject.file(it).isFile }
            check(missing.isEmpty()) { "Required documentation is missing: ${missing.joinToString()}" }

            val localLink = Regex("""\[[^]]*]\((?!https?://|mailto:|#)([^)]+)\)""")
            val broken = mutableListOf<String>()

            markdownFiles.files.forEach { markdown ->
                localLink.findAll(markdown.readText()).forEach { match ->
                    val rawTarget = match.groupValues[1].substringBefore("#").substringBefore(" \"")
                    val target = rawTarget.removePrefix("<").removeSuffix(">")
                    if (
                        target.isNotBlank() &&
                        !markdown.parentFile
                            .resolve(target)
                            .toPath()
                            .normalize()
                            .toFile()
                            .exists()
                    ) {
                        broken += "${markdown.relativeTo(rootDir).path} -> $target"
                    }
                }
            }

            check(broken.isEmpty()) { "Broken local Markdown links:\n${broken.joinToString("\n")}" }
        }
    }

val observabilityValidate =
    tasks.register<Exec>("observabilityValidate") {
        group = "verification"
        description = "Validates Prometheus, OpenTelemetry Collector, and Grafana provisioning."
        inputs.files(
            layout.projectDirectory.file("compose.yaml"),
            layout.projectDirectory.dir("infra/otel"),
            layout.projectDirectory.dir("infra/prometheus"),
            layout.projectDirectory.dir("infra/grafana"),
            layout.projectDirectory.file("scripts/validate-observability"),
        )
        commandLine(
            layout.projectDirectory
                .file("scripts/validate-observability")
                .asFile.absolutePath,
        )
    }

tasks.register("verify") {
    group = "verification"
    description = "Runs every LedgerFlow completion check."
    dependsOn(
        tasks.named("spotlessCheck"),
        staticAnalysis,
        test,
        integrationTest,
        architectureTest,
        openApiValidate,
        mockProviderOpenApiValidate,
        composeValidate,
        observabilityValidate,
        documentationCheck,
    )
}

tasks.register<Exec>("securityScan") {
    group = "verification"
    description = "Scans repository secrets, packaged dependencies, and Compose images."
    commandLine(
        layout.projectDirectory
            .file("scripts/security-scan")
            .asFile.absolutePath,
    )
}
