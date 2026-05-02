package com.bliss.game.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("game/api")

    @Test
    fun `dto package does not import domain types`() {
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
        // CLAUDE.md: cross-context imports forbidden; game/ talks to grid/ via HTTP only.
        apiScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") &&
                    !import.name.startsWith("com.bliss.game.")
            }
        }
    }

    @Test
    fun `api has no database driver imports`() {
        // game/ is in-memory v1 (ADR-0018 §3); persistence belongs in :game:infrastructure.
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
