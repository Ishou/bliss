plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    `java-test-fixtures`
}

val ktorVersion = "3.4.3"
val testcontainersVersion = "1.21.4"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))

    // UUIDv7 generation for ItemId / RatingId (ADR-0003 §6).
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    // Ktor HTTP client - identity-api /v1/me session verification.
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

    // NATS JetStream client - user.deleted consumer (ADR-0049).
    implementation("io.nats:jnats:2.20.6")

    // Lingua language detector - LinguaLanguageDetector implementation of the
    // application-layer port (deferred from PR4 to keep that PR cap-clean).
    implementation("com.github.pemistahl:lingua:1.2.2")

    testFixturesImplementation(project(":survey:domain"))
    testFixturesImplementation(project(":survey:application"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test {
    useJUnitPlatform()
}
