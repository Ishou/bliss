plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":grid:domain"))

    // Postgres pool + migrations — shared between grid-api and grid-worker.
    implementation("org.postgresql:postgresql:42.7.10")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:11.10.4")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.4")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Commons CSV — RFC 4180 reader for the words-<lang>.csv corpus (ADR-0013 §8).
    // Worker writes the same format with the same library.
    implementation("org.apache.commons:commons-csv:1.12.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
}

tasks.test {
    useJUnitPlatform()
}
