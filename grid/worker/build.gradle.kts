// grid/worker — Kotlin CLI module (ADR-0013 §7). Sibling to grid/api;
// depends on grid/domain only, never on grid/api (CLAUDE.md bounded-context rule).
// Sub-commands: import-words (PR2), generate-clues (PR3).
// shadowJar: build/libs/grid-worker-<version>-all.jar (Dockerfile lands in PR5).

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "9.4.1"
}

version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// Versions mirror grid/api so the two modules speak the same Flyway/JDBC stack.
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val cliktVersion = "5.1.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val postgresqlJdbcVersion = "42.7.10"
val hikariVersion = "7.0.2"
val flywayVersion = "12.4.0"
val testcontainersVersion = "1.21.4"
val kotestPropertyVersion = "5.9.1"

application {
    mainClass.set("com.bliss.grid.worker.MainKt")
}

dependencies {
    implementation(project(":grid:domain"))

    // clikt 5.x is multiplatform; pull the JVM artifact explicitly.
    implementation("com.github.ajalt.clikt:clikt-jvm:$cliktVersion")

    implementation("org.postgresql:postgresql:$postgresqlJdbcVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")

    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // kotest-property's `checkAll` is a `suspend fun`; runBlocking comes from kotlinx-coroutines.
    testImplementation("io.kotest:kotest-property-jvm:$kotestPropertyVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("grid-worker")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
