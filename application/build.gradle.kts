import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

plugins {
    id("org.springframework.boot")
}

description = "LedgerFlow deployable application"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String
val openTelemetryInstrumentationVersion =
    rootProject.extra["openTelemetryInstrumentationVersion"] as String

val integrationTest =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }

val architectureTest =
    sourceSets.create("architectureTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)
configurations[architectureTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[architectureTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

dependencies {
    implementation(project(":modules:ledger"))
    implementation(project(":modules:messaging"))
    implementation(project(":modules:notifications"))
    implementation(project(":modules:operations"))
    implementation(project(":modules:orders"))
    implementation(project(":modules:payments"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(
        platform(
            "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:" +
                openTelemetryInstrumentationVersion,
        ),
    )
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-test")

    // Spring Boot's dependency BOM imports the Testcontainers BOM; keep these modules versionless.
    add(
        integrationTest.implementationConfigurationName,
        "org.springframework.boot:spring-boot-testcontainers",
    )
    add(
        integrationTest.implementationConfigurationName,
        "org.testcontainers:testcontainers-junit-jupiter",
    )
    add(
        integrationTest.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql",
    )
    add(
        integrationTest.implementationConfigurationName,
        "org.testcontainers:testcontainers-kafka",
    )
    add(
        integrationTest.implementationConfigurationName,
        "org.testcontainers:testcontainers-toxiproxy",
    )
    add(
        integrationTest.implementationConfigurationName,
        "com.redis:testcontainers-redis",
    )
    add(
        integrationTest.implementationConfigurationName,
        "io.opentelemetry:opentelemetry-sdk-testing",
    )

    add(
        architectureTest.implementationConfigurationName,
        platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"),
    )
    add(
        architectureTest.implementationConfigurationName,
        "org.springframework.modulith:spring-modulith-starter-test",
    )
    add(
        architectureTest.implementationConfigurationName,
        "com.tngtech.archunit:archunit-junit5:1.4.2",
    )
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against PostgreSQL Testcontainers."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    systemProperty("spring.test.context.cache.maxSize", "8")
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("architectureTest") {
    description = "Runs Spring Modulith and ArchUnit architecture tests."
    group = "verification"
    testClassesDirs = architectureTest.output.classesDirs
    classpath = architectureTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.named("check") {
    dependsOn("integrationTest", "architectureTest")
}

tasks.register<JavaExec>("runMockPaymentProvider") {
    group = "performance"
    description =
        "Runs the deterministic mock payment provider fixture as a standalone local " +
        "process for performance/scripts (see docs/plans/portfolio-extension-execplan.md)."
    classpath = integrationTest.runtimeClasspath
    mainClass = "com.ledgerflow.testing.payment.StandaloneMockPaymentProviderServer"
}

tasks.register("printMockProviderClasspath") {
    group = "performance"
    description =
        "Prints the runtime classpath needed to run StandaloneMockPaymentProviderServer " +
        "from a container that mounts this repository and the Gradle cache read-only."
    doLast {
        println("MOCK_PROVIDER_CLASSPATH=" + integrationTest.runtimeClasspath.asPath)
    }
}

tasks.register<Copy>("exportMockProviderRuntime") {
    group = "performance"
    description =
        "Copies StandaloneMockPaymentProviderServer's compiled classes and its actual " +
        "runtime dependency (Jackson only; verified by running the class with just these " +
        "jars) into build/mock-provider-runtime, for a minimal, self-contained container " +
        "image (see deploy/kind/mock-provider.Dockerfile, Milestone 4) that needs neither " +
        "Gradle, the full integrationTest classpath, nor a mounted repository checkout at " +
        "runtime."
    from(integrationTest.output.classesDirs) {
        into("classes")
    }
    from(
        integrationTest.runtimeClasspath.filter {
            it.isFile && it.name.startsWith("jackson-") && it.name.endsWith(".jar")
        },
    ) {
        into("libs")
    }
    into(layout.buildDirectory.dir("mock-provider-runtime"))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "ledgerflow.jar"
    // Reproducible build: strip per-build file timestamps and fix entry order so two
    // builds from the same source produce a byte-identical jar (verified in
    // docs/container-hardening.md by comparing SHA-256 across two clean builds).
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
