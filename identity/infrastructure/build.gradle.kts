plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    `java-test-fixtures`
}

val ktorVersion = "3.4.3"

kotlin {
    jvmToolchain(21)
}

val testcontainersVersion = "1.21.4"

dependencies {
    implementation(project(":identity:domain"))
    implementation(project(":identity:application"))

    // UUIDv7 generation for UserId / SessionId / AuthAttemptId (ADR-0003 §6).
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    // JOSE/JWT: ID-token verification and Apple client-assertion signing (ADR-0046).
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")

    // Ktor HTTP client — token-endpoint code exchange (ADR-0006).
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.postgresql:postgresql:42.7.11")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:12.6.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.6.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testFixturesImplementation(project(":identity:domain"))
    testFixturesImplementation(project(":identity:application"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.lemonappdev:konsist:0.17.3")

    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
