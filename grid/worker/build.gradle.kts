// grid/worker — CronJob CLI for daily puzzle pre-generation (ADR-0042).

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "9.4.1"
}

version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

val ktorVersion = "3.4.3"
val junitVersion = "5.11.4"
val assertkVersion = "0.28.1"
val konsistVersion = "0.17.3"
val testcontainersVersion = "1.21.4"
val logbackVersion = "1.5.32"
val logstashEncoderVersion = "9.0"

application {
    mainClass.set("com.bliss.grid.worker.MainKt")
}

dependencies {
    constraints {
        // Mirror grid/api's CVE pins so the worker shadowJar carries the same
        // hardened transitive set when run as a CronJob.
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.3")
        implementation("tools.jackson.core:jackson-core:3.1.1")
    }

    implementation(project(":grid:domain"))
    implementation(project(":grid:application"))
    implementation(project(":grid:infrastructure"))

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
}

// CsvWordRepository.frenchFromClasspath() reads /words/words-fr.csv plus the
// themed overlays from the runtime classpath. The canonical copy lives in
// :grid:api/src/main/resources/words/ (the API has historically been the
// service that loads them). The worker shadowJar needs the same files at
// runtime; rather than duplicate the corpus on disk, declare a copy task
// that stages just the words/ subtree into this module's resource output so
// any update in :grid:api propagates here without resource-overlap with
// the worker's own logback.xml.
val copyWordsCorpus =
    tasks.register<Copy>("copyWordsCorpus") {
        from(rootProject.layout.projectDirectory.dir("grid/api/src/main/resources/words"))
        into(layout.buildDirectory.dir("generated/wordsResources/words"))
    }

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/wordsResources").map { it.asFile })
}

tasks.named("processResources") {
    dependsOn(copyWordsCorpus)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("grid-worker")
    archiveClassifier.set("all")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
