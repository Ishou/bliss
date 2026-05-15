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
 * Generates a real puzzle, inspects the grid layout cell-by-cell to spot
 * structural anomalies (missing clue text, unreachable letters, etc.).
 *
 * A previous test asserted "no BlockCellDto cells inside the layout". That
 * invariant held for the boundary-skeleton generator that pre-dated the
 * bitmask-CSP generator (ADR-0039, PR #368), which treats interior black
 * cells as primary search state. Block cells in mots-fleches grids are part
 * of the public schema (`BlockCell` in `openapi.yaml`), and the mapper
 * emits them deliberately for any `EmptyCell` / `null` position. The
 * remaining tests verify the structural properties that still must hold:
 * area arithmetic and clue-walk reachability.
 */
class GenerationInspectionTest {
    @Test
    fun `cell counts add up - letter cells plus distinct clue positions equals grid area`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultPuzzleConstraints()

        for (seed in 0L until 3L) {
            val grid = generator.generate(constraints, Random(seed)) ?: continue
            val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)
            val area = puzzle.width * puzzle.height
            val letterPositions =
                puzzle.cells
                    .filterIsInstance<LetterCellDto>()
                    .map { it.position.row to it.position.column }
                    .toSet()
            val cluePositions =
                puzzle.cells
                    .filterIsInstance<DefinitionCellDto>()
                    .map { it.position.row to it.position.column }
                    .toSet()
            val blockPositions =
                puzzle.cells
                    .filterIsInstance<BlockCellDto>()
                    .map { it.position.row to it.position.column }
                    .toSet()
            check(letterPositions.size + cluePositions.size + blockPositions.size == area) {
                "seed=$seed area=$area letter=${letterPositions.size} clue=${cluePositions.size} " +
                    "block=${blockPositions.size}: sum != area"
            }
            check((letterPositions intersect cluePositions).isEmpty()) {
                "seed=$seed: positions in both letter and clue sets — invariant violated"
            }
        }
    }

    @Test
    fun `every letter cell is reachable from at least one clue's walk path`() {
        // Mirrors the frontend's path-walking in useGridNavigation: from each definition
        // cell, walk along the arrow until hitting a non-letter or boundary. Every letter
        // cell in the response must appear in at least one clue's walk — otherwise the
        // player has no clue context for it and can't fill it in.
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultPuzzleConstraints()

        var generated = 0
        var withUnreachable = 0
        val unreachableDetails = mutableListOf<String>()
        for (seed in 0L until 5L) {
            val grid = generator.generate(constraints, Random(seed)) ?: continue
            generated++
            val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)

            val byPos = puzzle.cells.associate { (it.position.row to it.position.column) to it }
            val letterPositions =
                puzzle.cells
                    .filterIsInstance<LetterCellDto>()
                    .map { it.position.row to it.position.column }
                    .toSet()
            val coveredByClue = HashSet<Pair<Int, Int>>()

            for (cell in puzzle.cells) {
                if (cell !is DefinitionCellDto) continue
                // Translate the wire arrow string back into the domain Direction so we
                // pull start/step offsets from the same source the generator did.
                // Avoids encoding arrow semantics in two places.
                val direction =
                    when (cell.arrow) {
                        "right" -> com.bliss.grid.domain.model.Direction.RIGHT
                        "down" -> com.bliss.grid.domain.model.Direction.DOWN
                        "down-right" -> com.bliss.grid.domain.model.Direction.DOWN_RIGHT
                        "right-down" -> com.bliss.grid.domain.model.Direction.RIGHT_DOWN
                        else -> error("unknown arrow ${cell.arrow}")
                    }
                val startDr = direction.startOffset.row.value
                val startDc = direction.startOffset.column.value
                val dr = direction.step.row.value
                val dc = direction.step.column.value
                var r = cell.position.row + startDr
                var c = cell.position.column + startDc
                while (r in 0 until puzzle.height && c in 0 until puzzle.width) {
                    val target = byPos[r to c]
                    if (target !is LetterCellDto) break
                    coveredByClue += r to c
                    r += dr
                    c += dc
                }
            }

            val unreachable = letterPositions - coveredByClue
            if (unreachable.isNotEmpty()) {
                withUnreachable++
                unreachableDetails += "seed=$seed unreachable letter cells: $unreachable"
            }
        }
        check(withUnreachable == 0) {
            "$withUnreachable of $generated grids have letter cells with no clue covering them\n" +
                unreachableDetails.joinToString("\n")
        }
    }
}
