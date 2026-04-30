package com.bliss.grid.application.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Enforces the application-layer dependency rules from CLAUDE.md / MANIFESTO:
 * application orchestrates domain use cases — it never imports infrastructure
 * adapters, framework code, or vendor SDKs.
 */
class ApplicationArchitectureTest {
    private val applicationScope = Konsist.scopeFromModule("grid/application")

    @Test
    fun `application has no infrastructure imports`() {
        applicationScope.files.assertFalse {
            it.hasImport { import -> import.name.contains("infrastructure") }
        }
    }

    @Test
    fun `application has no api or worker imports`() {
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid.api") ||
                    import.name.startsWith("com.bliss.grid.worker")
            }
        }
    }

    @Test
    fun `application has no framework imports`() {
        val forbiddenPrefixes =
            listOf(
                "org.springframework",
                "jakarta.",
                "javax.",
                "io.ktor",
                "org.http4k",
                "org.jetbrains.exposed",
                "io.micronaut",
                "com.github.ajalt.clikt",
            )
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }
}
