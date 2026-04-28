// grid/api — Ktor JVM HTTP server (ADR-0006). Outermost layer of the grid
// bounded context per ADR-0001 §1; the v1 health-only skeleton has no
// inter-module deps. The first product endpoint pulls in grid/application/.
//
// shadowJar lands at: build/libs/grid-api-<version>-all.jar
// Workstream 2's Dockerfile and deploy workflow rely on this exact name.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

// Bump on each release. Workstream 2 reads this for artifact name.
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val javaUuidGeneratorVersion = "4.3.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val postgresqlJdbcVersion = "42.7.10"
val hikariVersion = "7.0.2"
val flywayVersion = "12.4.0"
val testcontainersVersion = "1.21.4"
val kotestPropertyVersion = "5.9.1"
val commonsCsvVersion = "1.12.0"

application {
    mainClass.set("com.bliss.grid.api.MainKt")
    // Ktor auto-reload: watches classpath dirs for class changes and reloads
    // modules without restart. Only affects `./gradlew :grid:api:run` — the
    // production Docker image runs `java -jar` directly (no application plugin).
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=true")
}

dependencies {
    // Grid bounded-context inner layers (ADR-0001 §1, MANIFESTO Architecture).
    // The api layer composes domain generation with infrastructure adapters
    // and maps domain types to wire DTOs (ADR-0003 §4).
    implementation(project(":grid:domain"))
    implementation(project(":grid:infrastructure"))

    // Ktor server core + Netty engine (ADR-0006 §1).
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // ContentNegotiation + kotlinx-serialization JSON (ADR-0006 §2).
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Status pages (RFC 7807) + call logging — ADR-0003 §6, MANIFESTO Observability.
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // CORS — browsers block wordsparrow.io → api.wordsparrow.io without it.
    // Previews are frontend-only via MSW (ADR-0007 §5), so the allowlist is
    // narrow: prod apex + www + local Vite dev.
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // UUID v7 generation (ADR-0003 §6 — wire convention: UUID v7 ids).
    implementation("com.fasterxml.uuid:java-uuid-generator:$javaUuidGeneratorVersion")

    // Structured JSON logging stack (ADR-0007 §7).
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    // Postgres JDBC + connection pool + Flyway (ADR-0013 §6).
    // The API runs Flyway on startup against the CNPG cluster (ADR-0009),
    // sharing the schema with the worker module (ADR-0013 §7).
    implementation("org.postgresql:postgresql:$postgresqlJdbcVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Apache Commons CSV — RFC 4180 reader for words-<lang>.csv (ADR-0013 §8).
    // Mirrors the worker's `export-words` writer dependency to keep the format
    // contract enforced by the same library on both sides.
    implementation("org.apache.commons:commons-csv:$commonsCsvVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")

    // Testcontainers — real Postgres for migration/contract tests (ADR-0013 §6).
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // Property-based tests for parsers and serialization (CLAUDE.md).
    testImplementation("io.kotest:kotest-property-jvm:$kotestPropertyVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("grid-api")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
