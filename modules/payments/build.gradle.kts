description = "Payments feature module"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String
val resilience4jVersion = rootProject.extra["resilience4jVersion"] as String

dependencies {
    implementation(project(":modules:operations"))
    implementation(platform("io.github.resilience4j:resilience4j-bom:$resilience4jVersion"))
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
