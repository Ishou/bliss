package com.bliss.game.application.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Mirrors `:grid:application`'s rules: application orchestrates the domain via ports
 * — never imports infrastructure adapters, framework code, or vendor SDKs, and never
 * pulls another bounded context's package (per ADR-0001 §1).
 */
class ApplicationArchitectureTest {
    private val applicationScope = Konsist.scopeFromModule("game/application")

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
                import.name.startsWith("com.bliss.game.api") ||
                    import.name.startsWith("com.bliss.game.worker")
            }
        }
    }

    @Test
    fun `application has no cross context imports`() {
        applicationScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.grid") }
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
                "kotlinx.serialization",
            )
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `application has no vendor sdk imports`() {
        val forbiddenPrefixes =
            listOf(
                "com.anthropic",
                "software.amazon",
                "com.amazonaws",
                "com.google.cloud",
                "com.azure",
            )
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }
}
