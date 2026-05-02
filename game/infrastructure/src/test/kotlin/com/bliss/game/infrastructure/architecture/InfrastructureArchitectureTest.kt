package com.bliss.game.infrastructure.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class InfrastructureArchitectureTest {
    private val infrastructureScope = Konsist.scopeFromModule("game/infrastructure")

    @Test
    fun `infrastructure has no api or worker imports`() {
        infrastructureScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.game.api") ||
                    import.name.startsWith("com.bliss.game.worker")
            }
        }
    }

    @Test
    fun `infrastructure has no cross context imports`() {
        infrastructureScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.grid") }
        }
    }

    @Test
    fun `infrastructure has no framework imports`() {
        val forbiddenPrefixes =
            listOf(
                "org.springframework",
                "jakarta.",
                "io.ktor",
                "org.http4k",
                "org.jetbrains.exposed",
                "io.micronaut",
                "kotlinx.serialization",
                "java.sql",
                "javax.sql",
                "com.zaxxer.hikari",
                "org.postgresql",
                "org.flywaydb",
            )
        infrastructureScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `infrastructure has no vendor sdk imports`() {
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
