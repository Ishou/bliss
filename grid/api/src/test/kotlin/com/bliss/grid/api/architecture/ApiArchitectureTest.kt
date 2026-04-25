package com.bliss.grid.api.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ApiArchitectureTest {
    private val apiScope = Konsist.scopeFromModule("grid/api")

    @Test
    fun `api layer does not import domain directly`() {
        apiScope.files.assertFalse {
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
}
