package com.bliss.survey.worker

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class WorkerArchitectureTest {
    private val workerScope = Konsist.scopeFromModule("survey/worker")

    @Test
    fun `worker does not import other bounded contexts`() {
        workerScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") &&
                    !import.name.startsWith("com.bliss.survey.")
            }
        }
    }

    @Test
    fun `worker does not import the api layer`() {
        workerScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.survey.api") }
        }
    }

    @Test
    fun `worker has a single top-level main entry point in Main_kt`() {
        val mains =
            workerScope
                .functions()
                .filter { it.name == "main" && it.isTopLevel }
        assertThat(mains).hasSize(1)
        assertThat(mains.single().containingFile.name).isEqualTo("Main")
    }
}
