plugins {
    kotlin("jvm")
    // Wire DTOs are @Serializable — kotlinx-serialization plugin generates the (de)serializers.
    kotlin("plugin.serialization") version "2.3.21"
}

kotlin {
    jvmToolchain(21)
}

// Versions mirrored from grid/api/build.gradle.kts so the Ktor stack stays uniform across
// bounded contexts. Bump in lockstep when grid moves.
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val javaUuidGeneratorVersion = "5.2.0"

// Versions mirrored from grid/infrastructure/build.gradle.kts so the Postgres / Flyway /
// Testcontainers stacks stay uniform across bounded contexts. Bump in lockstep when grid moves.
val testcontainersVersion = "1.21.4"

dependencies {
    implementation(project(":game:domain"))
    implementation(project(":game:application"))
    // suspend adapters need coroutines-core at runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Ktor client + CIO engine — calls grid's REST API from HttpPuzzleProvider.
    // CIO matches grid/api's server-side engine choice (ADR-0006); no extra native deps.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // UUID v7 generation for fresh PuzzleIds (ADR-0003 §6).
    implementation("com.fasterxml.uuid:java-uuid-generator:$javaUuidGeneratorVersion")

    // Postgres pool + Flyway — staged for PostgresLobbyRepository (PR #5).
    // Today these only back the V1 Flyway migration + MigrationTest; the Konsist
    // production-source ban in InfrastructureArchitectureTest keeps them out of
    // src/main/kotlin until the adapter lands.
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:12.6.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.6.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("com.lemonappdev:konsist:0.17.3")

    // Testcontainers — real Postgres for the V1 Flyway MigrationTest and the
    // forthcoming PostgresLobbyRepository contract tests (PR #5).
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // MockEngine — fake transport for HttpPuzzleProvider tests. Avoids Testcontainers
    // for an HTTP-only adapter (no DB needed); MockEngine starts in microseconds.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
