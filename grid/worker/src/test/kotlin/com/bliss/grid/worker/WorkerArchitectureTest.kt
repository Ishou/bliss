package com.bliss.grid.worker

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

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

    @Test
    fun `worker has a single top-level main entry point in Main_kt`() {
        val mains =
            workerScope
                .functions()
                .filter { it.name == "main" && it.isTopLevel }
        check(mains.size == 1) {
            "expected exactly one top-level main(), found ${mains.size}: ${mains.map { it.location }}"
        }
        val main = mains.single()
        check(main.containingFile.name == "Main") {
            "main() must live in Main.kt, found in ${main.containingFile.name}.kt"
        }
    }
}
