description = "Operations feature module"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.micrometer:micrometer-core")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
