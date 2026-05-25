package com.bliss.survey.infrastructure.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class InfrastructureArchitectureTest {
    private val infrastructureScope = Konsist.scopeFromModule("survey/infrastructure")

    @Test
    fun `infrastructure has no api imports`() {
        infrastructureScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.survey.api") }
        }
    }

    @Test
    fun `infrastructure has no cross context imports`() {
        infrastructureScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game") ||
                    import.name.startsWith("com.bliss.identity")
            }
        }
    }

    @Test
    fun `production infrastructure has no banned server framework imports`() {
        val forbiddenPrefixes =
            listOf(
                "org.springframework",
                "jakarta.",
                "io.ktor.server",
                "org.http4k",
                "org.jetbrains.exposed",
                "io.micronaut",
            )
        Konsist
            .scopeFromProduction(moduleName = "survey/infrastructure")
            .files
            .assertFalse {
                it.hasImport { import ->
                    forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
                }
            }
    }

    @Test
    fun `infrastructure has no banned vendor sdk imports`() {
        val forbiddenPrefixes =
            listOf(
                "com.anthropic",
                "software.amazon",
                "com.amazonaws",
                "com.google.cloud",
                "com.azure",
            )
        infrastructureScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }
}
