package com.bliss.identity.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("identity/api")

    @Test
    fun `api does not import other bounded contexts`() {
        apiScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game")
            }
        }
    }

    @Test
    fun `api has no vendor sdk imports`() {
        // api may import infrastructure for DI, but the JOSE/JWKS plumbing belongs in
        // infrastructure - api should not pull `com.nimbusds.*` directly.
        val forbiddenPrefixes =
            listOf(
                "com.google.cloud",
                "com.google.api",
                "software.amazon",
                "com.amazonaws",
                "com.azure",
                "com.nimbusds",
            )
        apiScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `dto package does not import domain types`() {
        // DTOs are pure wire types (ADR-0003 §4). Mapping happens in routes, not DTOs.
        apiScope.files
            .withPackage("com.bliss.identity.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("com.bliss.identity.domain") }
            }
    }

    @Test
    fun `dto package has no ktor imports`() {
        apiScope.files
            .withPackage("com.bliss.identity.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("io.ktor") }
            }
    }
}
