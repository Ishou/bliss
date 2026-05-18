// game/api — Ktor JVM HTTP + WebSocket server (ADR-0006, ADR-0018 §3).
// Outermost layer of the game bounded context per ADR-0001 §1. v1 is
// in-memory only — no Postgres, no Flyway, no HikariCP (ADR-0018 §3).
// REST endpoints + the WebSocket route land in Wave F PR #9 / #10.
//
// shadowJar lands at: build/libs/game-api-<version>-all.jar
// Used by game/api/Dockerfile.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

// Bump on each release. Dockerfile / chart consume this for artifact naming.
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// Versions mirrored from grid/api/build.gradle.kts so the Ktor stack stays uniform across
// bounded contexts. Bump in lockstep when grid moves.
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val kotestPropertyVersion = "6.1.11"

application {
    mainClass.set("com.bliss.game.api.MainKt")
    // Ktor auto-reload during `./gradlew :game:api:run`. Production runs `java -jar`.
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

    // Game bounded-context inner layers (ADR-0001 §1, MANIFESTO Architecture).
    implementation(project(":game:domain"))
    implementation(project(":game:application"))
    implementation(project(":game:infrastructure"))

    // NATS client — Module.kt holds the Connection/JetStream returned by the factory
    // to register an ApplicationStopped close-hook (ADR-0049). Gradle implementation
    // scope is not transitive across module boundaries.
    implementation("io.nats:jnats:2.20.6")

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

    // CORS — wordsparrow.io frontend talks to this API in the browser.
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Default-headers — applies fixed security headers (HSTS, X-Content-Type-Options,
    // Referrer-Policy, X-Frame-Options) to every response.
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    // WebSockets — game/api is the first WS-using service in this repo
    // (ADR-0018 §3 / ADR-0006). The route itself lands in Wave F PR #10.
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    // Ktor client + CIO engine — Module.kt instantiates an HttpClient for
    // HttpPuzzleProvider (which lives in :game:infrastructure but is a
    // pure suspend adapter). `implementation` deps are not exposed across
    // module boundaries, so the api module declares its own client.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Structured JSON logging stack (ADR-0007 §7), parity with grid/api.
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    // Ktor's testApplication exposes a client; enabling the WebSockets plugin
    // on it lets us drive `/v1/lobbies/{lobbyId}/ws` from tests.
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
    testImplementation("io.kotest:kotest-property-jvm:$kotestPropertyVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("game-api")
    archiveClassifier.set("all")
    // Shadow 9.x defaults `duplicatesStrategy` to EXCLUDE on the shadow jar
    // task. EXCLUDE drops same-path duplicates BEFORE they reach the
    // transformer chain, so `mergeServiceFiles()` only ever sees one of
    // each `META-INF/services/<name>` file across the input jars and the
    // others are silently lost. Documented in shadow's source and on
    // https://gradleup.com/shadow/configuration/merging/#handling-duplicates-strategy
    //
    // Concrete prod blast radius for game-api: flyway-core ships 28
    // entries in `META-INF/services/org.flywaydb.core.extensibility.Plugin`
    // (including `CoreResourceTypeProvider`, the SPI registration that
    // tells Flyway `.sql` is a migration extension and that registers
    // the `classpath:` location prefix), flyway-database-postgresql
    // ships its own 3-entry copy at the same path, and EXCLUDE kept only
    // postgresql's. `Flyway.<init>` then autoloaded the built-in
    // `classpath:db/callback` callback path, the LocationParser found no
    // registered prefix providers, and the JVM crashed at boot with
    // `FlywayException: Unknown prefix for location (should be one of ):
    // classpath:db/callback` — observed 2026-05-13 as a 5-restart
    // crashloop blocking every game-api deploy after the postgres cutover.
    // Mirrors grid/api/build.gradle.kts's identical fix.
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
