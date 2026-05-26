plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.bliss.survey.worker.MainKt")
    applicationName = "survey-worker"
}

val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val testcontainersVersion = "1.21.4"
val konsistVersion = "0.17.3"

dependencies {
    implementation(project(":survey:domain"))
    implementation(project(":survey:application"))
    implementation(project(":survey:infrastructure"))

    // UUIDv7 generator for the IdGenerator port.
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
}

tasks.test {
    useJUnitPlatform()
}
