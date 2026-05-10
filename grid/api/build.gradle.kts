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
val javaUuidGeneratorVersion = "5.2.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val postgresqlJdbcVersion = "42.7.11"
val hikariVersion = "7.0.2"
// Flyway 12.x auto-loads callbacks from `classpath:db/callback` inside
// `Flyway.<init>()` — see ClassicConfiguration.loadCallbackLocation. In our
// shadowJar, that path crashes on startup with
//   FlywayException: Unknown prefix for location (should be one of ): classpath:db/callback
// (note the empty `( )` — even `classpath:` isn't recognized). Diagnosed
// from Deploy API (k8s) run #19. Pinning to the last 11.x release
// sidesteps the entire callback-autoload codepath; the API surface
// (`configure().dataSource().locations().load().migrate()`) is identical
// between 11 and 12, and `flyway-database-postgresql` exists in both.
// Upgrade revisit: bump back to 12.x once the upstream / shadowJar
// interaction is fixed (re-run this image, watch for the same crash
// signature in the new pod's previous-container logs).
val flywayVersion = "11.10.4"
val testcontainersVersion = "1.21.4"
val kotestPropertyVersion = "6.1.11"
val commonsCsvVersion = "1.12.0"

application {
    mainClass.set("com.bliss.grid.api.MainKt")
    // Ktor auto-reload: watches classpath dirs for class changes and reloads
    // modules without restart. Only affects `./gradlew :grid:api:run` — the
    // production Docker image runs `java -jar` directly (no application plugin).
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=true")
}

dependencies {
    // Pin transitive Netty 4.2.x to 4.2.13.Final (current Ktor pin is .12).
    // Fixes a cluster of HTTP-request-smuggling, decompression-bomb, and
    // resource-exhaustion CVEs in netty-codec-http / -http2 / -compression
    // and an epoll DoS in -transport-native-epoll. GHSA refs: v8h7-rr48-vmmv,
    // m4cv-j2px-7723, xxqh-mfjm-7mv9, 38f8-5428-x5cv, 57rv-r2g8-2cj3,
    // f6hv-jmp6-3vwv, rwm7-x88c-3g2p, mj4r-2hfc-f8p6.
    //
    // Pin transitive Jackson to versions that fix DoS CVEs in number-length
    // and document-length parsing limits.
    //   com.fasterxml.jackson.core (Jackson 2.x line, used by Logstash
    //     encoder): 2.21.1 — GHSA-72hv-8253-57qq.
    //   tools.jackson.core (Jackson 3.x line, pulled in transitively): 3.1.1
    //     — GHSA-72hv-8253-57qq, GHSA-6v53-7c9g-w56r, GHSA-2m67-wjpj-xhg9.
    constraints {
        implementation("io.netty:netty-codec-http:4.2.13.Final")
        implementation("io.netty:netty-codec-http2:4.2.13.Final")
        implementation("io.netty:netty-codec-compression:4.2.13.Final")
        implementation("io.netty:netty-transport-native-epoll:4.2.13.Final")
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.3")
        implementation("tools.jackson.core:jackson-core:3.1.1")
    }

    // Grid bounded-context inner layers (ADR-0001 §1, MANIFESTO Architecture).
    // The api layer composes domain generation with infrastructure adapters
    // and maps domain types to wire DTOs (ADR-0003 §4).
    implementation(project(":grid:domain"))
    implementation(project(":grid:application"))
    implementation(project(":grid:infrastructure"))

    // Ktor server core + Netty engine (ADR-0006 §1).
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // ContentNegotiation + kotlinx-serialization JSON (ADR-0006 §2).
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Status pages (RFC 7807) + call logging + correlation IDs — ADR-0003 §6, MANIFESTO Observability.
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")

    // CORS — browsers block wordsparrow.io → api.wordsparrow.io without it.
    // Previews are frontend-only via MSW (ADR-0007 §5), so the allowlist is
    // narrow: prod apex + www + local Vite dev.
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Default-headers — applies fixed security headers (HSTS, X-Content-Type-Options,
    // Referrer-Policy, X-Frame-Options) to every response.
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    // Ktor client — used by Module.kt to wire MatomoAnalyticsAdapter (ADR-0025).
    // The client posts events to the self-hosted Matomo Tracking API.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

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
    // Shadow 9.x defaults `duplicatesStrategy` to EXCLUDE on the shadow jar
    // task. EXCLUDE drops same-path duplicates BEFORE they reach the
    // transformer chain, so `mergeServiceFiles()` only ever sees one of
    // each `META-INF/services/<name>` file across the input jars and the
    // others are silently lost. Documented in shadow's source (see
    // ShadowJar.kt's duplicatesStrategy KDoc) and on
    // https://gradleup.com/shadow/configuration/merging/#handling-duplicates-strategy
    //
    // The concrete prod blast radius for grid-api: flyway-core ships 28
    // entries in `META-INF/services/org.flywaydb.core.extensibility.Plugin`
    // (including `CoreResourceTypeProvider`, the SPI registration that
    // tells Flyway `.sql` is a migration extension), flyway-database-
    // postgresql ships its own 3-entry copy at the same path, and EXCLUDE
    // kept only postgresql's. Flyway then booted, connected fine, scanned
    // db/migration/, and rejected every .sql because no resource-type
    // provider claimed the extension — silent zero-migration boot.
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
