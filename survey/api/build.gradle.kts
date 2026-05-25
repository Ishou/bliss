// survey/api — Ktor JVM HTTP server for the survey bounded context (ADR-0056, ADR-0006).
// Outermost layer; only this module imports Ktor. Mirrors identity/api's pinning so the
// Ktor stack stays uniform across bounded contexts.
//
// shadowJar lands at: build/libs/survey-api-<version>-all.jar
// Used by survey/api/Dockerfile (Phase 7).

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// Versions mirrored from identity/api/build.gradle.kts so the Ktor stack stays uniform.
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"

application {
    mainClass.set("com.bliss.survey.api.MainKt")
}

dependencies {
    // Survey bounded-context inner layers (ADR-0001 §1).
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))
    implementation(project(":survey:infrastructure"))

    // Ktor server core + CIO engine.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")

    // ContentNegotiation + kotlinx-serialization JSON.
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Status pages (RFC 7807) + call logging + CORS.
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Structured JSON logging stack (ADR-0007 §7), parity with identity/api.
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("survey-api")
    archiveClassifier.set("all")
    // Shadow 9.x defaults `duplicatesStrategy` to EXCLUDE on the shadow jar task,
    // which drops same-path duplicates before mergeServiceFiles sees them — that
    // silently loses Flyway's SPI registrations and crashes the JVM at boot with
    // `FlywayException: Unknown prefix for location`. Mirrors identity/api.
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
