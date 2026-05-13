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

dependencies {
    implementation(project(":grid:application"))
    implementation(project(":grid:infrastructure"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
}

tasks.test {
    useJUnitPlatform()
}
