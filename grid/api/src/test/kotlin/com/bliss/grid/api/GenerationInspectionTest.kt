package com.bliss.grid.api

import com.bliss.grid.api.infrastructure.words.CsvWordRepository
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.api.routes.defaultConstraints
import com.bliss.grid.domain.generation.GridGenerator
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Generates a real puzzle, prints the grid layout cell-by-cell so we can spot
 * structural anomalies (block cells inside the layout, missing clue text, etc.).
 */
class GenerationInspectionTest {
    @Test
    fun `no grid produced over 100 seeds should contain block cells inside`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultConstraints()

        var generated = 0
        var withBlocks = 0
        for (seed in 0L until 100L) {
            val grid = generator.generate(constraints, Random(seed)) ?: continue
            generated++
            val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now())
            val blocks = puzzle.cells.count { it.javaClass.simpleName == "BlockCellDto" }
            if (blocks > 0) {
                withBlocks++
                println("seed=$seed blocks=$blocks")
                val byPos = puzzle.cells.groupBy { (it.position.row to it.position.column) }
                for (r in 0 until puzzle.height) {
                    val sb = StringBuilder()
                    for (c in 0 until puzzle.width) {
                        val cells = byPos[r to c].orEmpty()
                        val short =
                            when {
                                cells.isEmpty() -> "?"
                                cells.any { it.javaClass.simpleName == "BlockCellDto" } -> "B"
                                cells.any { it.javaClass.simpleName == "DefinitionCellDto" } -> "C"
                                else -> "L"
                            }
                        sb.append(short).append(' ')
                    }
                    println(sb.toString())
                }
                println("---")
            }
        }
        println("summary: generated=$generated withBlocks=$withBlocks")
        check(withBlocks == 0) { "$withBlocks of $generated grids contain block cells — generator bug" }
    }

    @Test
    fun `cell counts add up — letter cells plus distinct clue positions equals grid area`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultConstraints()

        for (seed in 0L until 5L) {
            val grid = generator.generate(constraints, Random(seed)) ?: continue
            val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now())
            val area = puzzle.width * puzzle.height
            val letterPositions =
                puzzle.cells
                    .filter { it.javaClass.simpleName == "LetterCellDto" }
                    .map { it.position.row to it.position.column }
                    .toSet()
            val cluePositions =
                puzzle.cells
                    .filter { it.javaClass.simpleName == "DefinitionCellDto" }
                    .map { it.position.row to it.position.column }
                    .toSet()
            val blockPositions =
                puzzle.cells
                    .filter { it.javaClass.simpleName == "BlockCellDto" }
                    .map { it.position.row to it.position.column }
                    .toSet()
            println(
                "seed=$seed area=$area letter=${letterPositions.size} clue=${cluePositions.size} block=${blockPositions.size} " +
                    "sum=${letterPositions.size + cluePositions.size + blockPositions.size} clues=${puzzle.clues.size}",
            )
            check(letterPositions.size + cluePositions.size + blockPositions.size == area) {
                "seed=$seed: letter+clue+block = ${letterPositions.size + cluePositions.size + blockPositions.size}, expected $area"
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
        val constraints = defaultConstraints()

        var generated = 0
        var withUnreachable = 0
        for (seed in 0L until 30L) {
            val grid = generator.generate(constraints, Random(seed)) ?: continue
            generated++
            val puzzle = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

            val byPos = puzzle.cells.associate { (it.position.row to it.position.column) to it }
            val letterPositions =
                puzzle.cells
                    .filter { it.javaClass.simpleName == "LetterCellDto" }
                    .map { it.position.row to it.position.column }
                    .toSet()
            val coveredByClue = HashSet<Pair<Int, Int>>()

            for (cell in puzzle.cells) {
                if (cell.javaClass.simpleName != "DefinitionCellDto") continue
                // Reflect the arrow string off the DTO.
                val arrowField = cell.javaClass.getDeclaredField("arrow").apply { isAccessible = true }
                val arrow = arrowField.get(cell) as String
                val startDr = if (arrow == "down" || arrow == "down-right") 1 else 0
                val startDc = if (arrow == "right" || arrow == "right-down") 1 else 0
                val dr = if (arrow == "down" || arrow == "right-down") 1 else 0
                val dc = if (arrow == "right" || arrow == "down-right") 1 else 0
                var r = cell.position.row + startDr
                var c = cell.position.column + startDc
                while (r in 0 until puzzle.height && c in 0 until puzzle.width) {
                    val target = byPos[r to c]
                    if (target?.javaClass?.simpleName != "LetterCellDto") break
                    coveredByClue += r to c
                    r += dr
                    c += dc
                }
            }

            val unreachable = letterPositions - coveredByClue
            if (unreachable.isNotEmpty()) {
                withUnreachable++
                println("seed=$seed unreachable letter cells: $unreachable")
            }
        }
        println("summary: generated=$generated withUnreachable=$withUnreachable")
        check(withUnreachable == 0) { "$withUnreachable of $generated grids have letter cells with no clue covering them" }
    }
}
