package com.bliss.grid.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("grid/api")

    @Test
    fun `dto package does not import domain types`() {
        // DTOs are pure wire types per ADR-0003 §4. Mapping happens in the
        // mapper package, never inside DTOs themselves.
        apiScope.files
            .withPackage("com.bliss.grid.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("com.bliss.grid.domain") }
            }
    }

    @Test
    fun `dto package contains no ktor imports`() {
        apiScope.files
            .withPackage("com.bliss.grid.api.dto..")
            .assertFalse {
                it.hasImport { import -> import.name.startsWith("io.ktor") }
            }
    }

    @Test
    fun `api does not import other bounded contexts`() {
        // CLAUDE.md: "Never import from another bounded context's domain or
        // application package." Within the grid context, the api/ layer maps
        // domain types ↔ DTOs (ADR-0003 §4); cross-context imports are banned.
        apiScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") &&
                    !import.name.startsWith("com.bliss.grid.")
            }
        }
    }
}
