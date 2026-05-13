package com.bliss.grid.worker.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Enforces the worker-layer dependency rules. The worker is the outermost
 * layer (peer of `:grid:api`) and may import domain / application /
 * infrastructure, but must not pull in another bounded context.
 */
class WorkerArchitectureTest {
    private val workerScope = Konsist.scopeFromModule("grid/worker")

    @Test
    fun `worker does not import other bounded contexts`() {
        workerScope.files.assertFalse {
            it.hasImport { import ->
                import.name.startsWith("com.bliss.") &&
                    !import.name.startsWith("com.bliss.grid.")
            }
        }
    }

    @Test
    fun `worker does not import the api layer`() {
        workerScope.files.assertFalse {
            it.hasImport { import -> import.name.startsWith("com.bliss.grid.api") }
        }
    }
}
