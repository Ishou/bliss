plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.bliss.grid.worker.MainKt")
}

val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"
val konsistVersion = "0.17.3"

dependencies {
    implementation(project(":grid:domain"))
    implementation(project(":grid:application"))
    implementation(project(":grid:infrastructure"))

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
}

tasks.test {
    useJUnitPlatform()
}
