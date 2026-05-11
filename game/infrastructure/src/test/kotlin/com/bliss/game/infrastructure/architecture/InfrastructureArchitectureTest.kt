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

    // Ktor client + kotlinx-serialization are legitimate adapter deps (HttpPuzzleProvider
    // talks to grid over REST). Server-side Ktor stays banned — that belongs in :game:api.
    // JDBC / Postgres / Flyway / HikariCP are scoped to PRODUCTION source only: the V1
    // Flyway MigrationTest (and the upcoming PostgresLobbyRepository contract test in
    // PR #5) legitimately import these from src/test. The production-source guard
    // narrows once PostgresLobbyRepository itself lands in PR #5.
    @Test
    fun `production infrastructure has no banned framework imports`() {
        val forbiddenPrefixes =
            listOf(
                "org.springframework",
                "jakarta.",
                "io.ktor.server",
                "org.http4k",
                "org.jetbrains.exposed",
                "io.micronaut",
                "java.sql",
                "javax.sql",
                "com.zaxxer.hikari",
                "org.postgresql",
                "org.flywaydb",
            )
        Konsist
            .scopeFromProduction(moduleName = "game/infrastructure")
            .files
            .assertFalse {
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
