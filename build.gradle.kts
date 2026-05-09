plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("com.diffplug.spotless") version "7.0.2"
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    // Force patched Jackson versions across all subprojects — closes DoS CVEs
    // GHSA-72hv-8253-57qq, GHSA-6v53-7c9g-w56r, GHSA-2m67-wjpj-xhg9.
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-core") {
                useVersion("2.21.1")
                because("GHSA-72hv-8253-57qq DoS fix")
            }
            if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
                useVersion("3.1.1")
                because("GHSA-72hv-8253-57qq / GHSA-6v53-7c9g-w56r / GHSA-2m67-wjpj-xhg9 DoS fix")
            }
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.5.0")
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.5.0")
        }
    }
}
