package com.bliss.identity.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class DomainArchitectureTest {
    private val domainScope = Konsist.scopeFromModule("identity/domain")

    @Test
    fun `domain has no infrastructure imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.contains("infrastructure") }
        }
    }

    @Test
    fun `domain has no application imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.identity.application") }
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
            )
        domainScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `domain has no vendor sdk imports`() {
        val forbiddenPrefixes =
            listOf(
                "com.google.cloud",
                "com.google.api",
                "software.amazon",
                "com.amazonaws",
                "com.azure",
                "com.auth0",
                "com.nimbusds",
            )
        domainScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `domain has no cross-context imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game")
            }
        }
    }
}
