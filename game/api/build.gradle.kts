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
val kotestPropertyVersion = "5.9.1"

application {
    mainClass.set("com.bliss.game.api.MainKt")
    // Ktor auto-reload during `./gradlew :game:api:run`. Production runs `java -jar`.
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=true")
}

dependencies {
    // Game bounded-context inner layers (ADR-0001 §1, MANIFESTO Architecture).
    implementation(project(":game:domain"))
    implementation(project(":game:application"))
    implementation(project(":game:infrastructure"))

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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
    testImplementation("io.kotest:kotest-property-jvm:$kotestPropertyVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("game-api")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
