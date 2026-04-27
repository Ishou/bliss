package com.bliss.grid.worker.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/** Architecture gate (ADR-0013 §7, CLAUDE.md): worker depends on grid/domain only. */
class WorkerArchitectureTest {
    private val workerScope = Konsist.scopeFromModule("grid/worker")

    @Test
    fun `worker does not import grid-api`() {
        workerScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.grid.api") }
        }
    }

    @Test
    fun `worker does not import other bounded contexts`() {
        workerScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") && !import.name.startsWith("com.bliss.grid.")
            }
        }
    }
}
