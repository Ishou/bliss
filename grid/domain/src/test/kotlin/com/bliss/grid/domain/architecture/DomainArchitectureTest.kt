package com.bliss.grid.domain.architecture

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class DomainArchitectureTest {

    private val domainScope = Konsist.scopeFromModule("grid/domain")

    @Test
    fun `domain has no infrastructure imports`() {
        domainScope.files.assertFalse {
            it.hasImport { import -> import.name.contains("infrastructure") }
        }
    }

    @Test
    fun `domain has no framework imports`() {
        val forbiddenPrefixes = listOf(
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
    fun `model package does not depend on generation`() {
        domainScope.files
            .withPackage("com.bliss.grid.domain.model..")
            .assertFalse {
                it.hasImport { import -> import.name.contains("generation") }
            }
    }

    @Test
    fun `model package does not depend on validation`() {
        domainScope.files
            .withPackage("com.bliss.grid.domain.model..")
            .assertFalse {
                it.hasImport { import -> import.name.contains("validation") }
            }
    }

    @Test
    fun `validation does not depend on generation`() {
        domainScope.files
            .withPackage("com.bliss.grid.domain.validation..")
            .assertFalse {
                it.hasImport { import -> import.name.contains("generation") }
            }
    }

    @Test
    fun `domain model value objects are data classes`() {
        domainScope.classes()
            .withPackage("com.bliss.grid.domain.model..")
            .withNameEndingWith("Cell", "Clue", "Position", "WordPlacement")
            .assertTrue {
                it.hasModifier(KoModifier.DATA)
            }
    }
}
