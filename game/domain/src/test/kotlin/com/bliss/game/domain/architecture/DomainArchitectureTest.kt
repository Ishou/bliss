package com.bliss.game.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class DomainArchitectureTest {
    private val domainScope = Konsist.scopeFromModule("game/domain")

    @Test
    fun `domain has no infrastructure imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.contains("infrastructure") }
        }
    }

    @Test
    fun `domain has no framework imports`() {
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
                "com.fasterxml.jackson",
            )
        domainScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `domain has no application imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.game.application") }
        }
    }

    @Test
    fun `domain has no cross-context grid imports`() {
        // ADR-0001 §1: cross-context imports forbidden. The game context owns its
        // own puzzle/cell shapes; communicate with grid via domain events only.
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.grid") }
        }
    }

    @Test
    fun `domain has no vendor sdk imports`() {
        val forbiddenPrefixes =
            listOf(
                "com.anthropic",
                "software.amazon",
                "com.amazonaws",
                "com.google.cloud",
                "com.azure",
            )
        domainScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }
}
