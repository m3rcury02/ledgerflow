description = "Ledger feature module"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String

dependencies {
    implementation(project(":modules:messaging"))
    implementation(project(":modules:payments"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("io.micrometer:micrometer-core")
    implementation("io.opentelemetry:opentelemetry-api")
    compileOnly(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
