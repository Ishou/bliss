package com.bliss.grid.api

import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Inspection-only: measures grid generation success across many seeds. */
@Tag("bench")
class GridSuccessRateTest {
    @Test
    fun `measure generation success rate over 200 seeds`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultPuzzleConstraints()

        val total = 200
        var ok = 0
        for (seed in 0L until total.toLong()) {
            if (generator.generate(constraints, Random(seed)) != null) ok++
        }
        val pct = ok * 100.0 / total
        println()
        println("=== generation success: $ok / $total = ${"%.1f".format(pct)}% ===")
        println()
    }
}
