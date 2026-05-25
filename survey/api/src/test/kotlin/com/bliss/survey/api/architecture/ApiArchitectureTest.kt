package com.bliss.survey.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("survey/api")

    @Test
    fun `api does not import other bounded contexts`() {
        apiScope.files.assertFalse { file ->
            file.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game") ||
                    import.name.startsWith("com.bliss.identity")
            }
        }
    }

    @Test
    fun `api has no vendor sdk imports`() {
        // ADR-0006: vendor SDKs live in infrastructure adapters, never api.
        val forbiddenPrefixes =
            listOf(
                "com.google.cloud",
                "com.google.api",
                "software.amazon",
                "com.amazonaws",
                "com.azure",
                "com.nimbusds",
            )
        apiScope.files.assertFalse { file ->
            file.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `dto package does not import domain types`() {
        // ADR-0003 §4 - DTOs are pure wire types; mapping happens in routes.
        apiScope.files
            .withPackage("com.bliss.survey.api.dto..")
            .assertFalse { file ->
                file.hasImport { import -> import.name.startsWith("com.bliss.survey.domain") }
            }
    }

    @Test
    fun `dto package has no ktor imports`() {
        apiScope.files
            .withPackage("com.bliss.survey.api.dto..")
            .assertFalse { file ->
                file.hasImport { import -> import.name.startsWith("io.ktor") }
            }
    }

    @Test
    fun `routes package does not import infrastructure adapter classes`() {
        // Routes depend on ports + use cases + domain only; adapters are wired in Main.kt.
        apiScope.files
            .withPackage("com.bliss.survey.api.routes..")
            .assertFalse { file ->
                file.hasImport { import -> import.name.startsWith("com.bliss.survey.infrastructure") }
            }
    }
}
