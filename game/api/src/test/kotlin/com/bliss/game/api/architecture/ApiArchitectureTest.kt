package com.bliss.game.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("game/api")

    @Test
    fun `dto package does not import domain types`() {
        // DTOs are pure wire types (ADR-0003 §4). Mapping happens in route /
        // mapper code, never inside DTOs themselves.
        apiScope.files
            .withPackage("com.bliss.game.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("com.bliss.game.domain") }
            }
    }

    @Test
    fun `dto package contains no ktor imports`() {
        apiScope.files
            .withPackage("com.bliss.game.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("io.ktor") }
            }
    }

    @Test
    fun `api does not import other bounded contexts`() {
        // CLAUDE.md: "Never import from another bounded context's domain or
        // application package." Communication crosses contexts via HTTP only —
        // game/ talks to grid/ through HttpPuzzleProvider in :game:infrastructure
        // (Ktor client, no shared types).
        apiScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") &&
                    !import.name.startsWith("com.bliss.game.")
            }
        }
    }

    @Test
    fun `api has no database driver imports`() {
        // game/api is HTTP-only in v1 (ADR-0018 §3): no Postgres, no JDBC,
        // no HikariCP, no Flyway. State lives in :game:infrastructure's
        // InMemoryLobbyRepository. If/when persistence lands, the imports
        // belong in :game:infrastructure, never here.
        val forbiddenPrefixes =
            listOf(
                "java.sql",
                "javax.sql",
                "com.zaxxer.hikari",
                "org.postgresql",
                "org.flywaydb",
                "org.jetbrains.exposed",
            )
        apiScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }
}
