description = "Messaging feature module"

val springModulithVersion = rootProject.extra["springModulithVersion"] as String

dependencies {
    implementation(project(":modules:operations"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    compileOnly(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
