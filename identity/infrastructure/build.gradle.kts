plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":identity:domain"))
    implementation(project(":identity:application"))

    // UUIDv7 generation for UserId / SessionId / AuthAttemptId (ADR-0003 §6).
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")

    testFixturesImplementation(project(":identity:domain"))
    testFixturesImplementation(project(":identity:application"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("io.kotest:kotest-property:6.1.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test {
    useJUnitPlatform()
}
