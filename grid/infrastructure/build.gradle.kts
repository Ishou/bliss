plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
}

kotlin {
    jvmToolchain(21)
}

val testcontainersVersion = "1.21.4"
val ktorVersion = "3.4.3"

dependencies {
    implementation(project(":grid:application"))
    implementation(project(":grid:domain"))

    // Postgres pool + migrations — used by BlissDatabase + PostgresPuzzleRepository.
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:11.10.4")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.4")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Ktor client + CIO engine — used by MatomoAnalyticsAdapter to POST events
    // to the self-hosted Matomo server (ADR-0025). Mirrors :game:infrastructure's
    // Ktor stack for consistency.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // kotlinx-serialization — JSONB encoding for the puzzles.payload column.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Commons CSV — RFC 4180 reader for the words-<lang>.csv corpus (ADR-0013 §8).
    implementation("org.apache.commons:commons-csv:1.12.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")

    // Testcontainers — real Postgres for PostgresPuzzleRepository / HintUsage contract tests.
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // MockEngine + coroutines-test — fake transport for MatomoAnalyticsAdapterTest.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
