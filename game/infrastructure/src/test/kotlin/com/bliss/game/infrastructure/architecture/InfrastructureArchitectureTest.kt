package com.bliss.game.infrastructure.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Wave D bootstrap rules for `:game:infrastructure`. The HTTP adapter
 * (Wave D PR #7 — HttpPuzzleProvider) and the Postgres adapter (later in
 * Wave D) will lift the Ktor and JDBC bans respectively in their own PRs;
 * until then this module ships a single in-memory adapter and stays free
 * of framework code. Mirrors `:grid:domain` and `:game:application`'s
 * Konsist style.
 */
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
