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

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "ledgerflow.jar"
}
