package com.bliss.survey.application.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApplicationArchitectureTest {
    private val applicationScope = Konsist.scopeFromModule("survey/application")

    @Test
    fun `application has no infrastructure imports`() {
        applicationScope.files.assertFalse {
            it.hasImport { import -> import.name.contains(".infrastructure.") }
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
                "com.github.pemistahl",
            )
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `application has no cross-context imports`() {
        applicationScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.grid") ||
                    import.name.startsWith("com.bliss.game") ||
                    import.name.startsWith("com.bliss.identity")
            }
        }
    }
}
