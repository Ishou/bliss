// identity/api — Ktor JVM HTTP server for the identity bounded context (ADR-0006, ADR-0044).
// Outermost layer; only this module imports Ktor. Mirrors game/api's pinning so the
// Ktor stack stays uniform across bounded contexts.
//
// shadowJar lands at: build/libs/identity-api-<version>-all.jar
// Used by identity/api/Dockerfile (Phase 4.5).

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

// Versions mirrored from game/api/build.gradle.kts so the Ktor stack stays uniform.
// Bump in lockstep when game/grid move.
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val kotestPropertyVersion = "6.1.11"

application {
    mainClass.set("com.bliss.identity.api.MainKt")
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

    // Identity bounded-context inner layers (ADR-0001 §1).
    implementation(project(":identity:domain"))
    implementation(project(":identity:application"))
    implementation(project(":identity:infrastructure"))

    // Ktor server core + Netty engine.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // ContentNegotiation + kotlinx-serialization JSON.
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Status pages (RFC 7807) + call logging + correlation IDs.
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")

    // Default-headers — fixed security headers (HSTS, X-Content-Type-Options,
    // Referrer-Policy, X-Frame-Options) on every response.
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    // CORS — cross-origin fetches from the frontend (ADR-0044).
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Ktor client + CIO engine — KtorOidcCodeExchanger (infrastructure) needs an engine;
    // implementation deps aren't transitive across module boundaries so api declares its own.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Structured JSON logging stack (ADR-0007 §7), parity with grid/api + game/api.
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
    testImplementation("io.kotest:kotest-property-jvm:$kotestPropertyVersion")
    testImplementation(testFixtures(project(":identity:infrastructure")))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("identity-api")
    archiveClassifier.set("all")
    // Shadow 9.x defaults `duplicatesStrategy` to EXCLUDE on the shadow jar task,
    // which drops same-path duplicates before mergeServiceFiles sees them — that
    // silently loses Flyway's SPI registrations and crashes the JVM at boot with
    // `FlywayException: Unknown prefix for location`. Mirrors grid/api + game/api.
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
