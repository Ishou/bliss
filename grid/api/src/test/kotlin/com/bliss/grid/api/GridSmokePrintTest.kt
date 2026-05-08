package com.bliss.grid.api

import com.bliss.grid.api.dto.BlockCellDto
import com.bliss.grid.api.dto.DefinitionCellDto
import com.bliss.grid.api.dto.LetterCellDto
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Inspection-only test that prints a real generated puzzle to stdout so a
 * human can eyeball clue + grid quality after a corpus update. Always passes.
 */
class GridSmokePrintTest {
    @Test
    fun `print one generated puzzle for visual inspection`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultPuzzleConstraints()

        var grid: com.bliss.grid.domain.model.Grid? = null
        var usedSeed = -1L
        var attempts = 0
        for (seed in 0L until 50L) {
            attempts++
            val g = generator.generate(constraints, Random(seed)) ?: continue
            grid = g
            usedSeed = seed
            break
        }
        println("=== generation: ${if (grid != null) "OK" else "FAIL"} after $attempts seed(s); used seed=$usedSeed ===")
        check(grid != null) { "grid generation failed across 50 seeds" }
        val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)
        val seed = usedSeed

        val byPos = puzzle.cells.associateBy { it.position.row to it.position.column }
        println()
        println("=== puzzle ${puzzle.width}x${puzzle.height} (seed=$seed) ===")
        for (r in 0 until puzzle.height) {
            val row = StringBuilder()
            for (c in 0 until puzzle.width) {
                val ch =
                    when (val cell = byPos[r to c]) {
                        is BlockCellDto -> '#'
                        is DefinitionCellDto -> '*'
                        is LetterCellDto -> '_' // wire shape no longer carries the canonical letter
                        null -> '?'
                        else -> '?'
                    }
                row.append(ch).append(' ')
            }
            println(row.toString())
        }
        println()
        println("=== clues (${puzzle.clues.size}) ===")
        puzzle.clues.forEach { c ->
            println("  ${c.direction[0]}@(r${c.start.row},c${c.start.column}) len=${c.length}: ${c.text}")
        }
        println()
    }
}
