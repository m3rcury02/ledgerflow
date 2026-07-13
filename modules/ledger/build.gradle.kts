description = "Ledger feature module"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String

dependencies {
    implementation(project(":modules:payments"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    compileOnly(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
