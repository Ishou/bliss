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
    fun `no grid produced over 30 seeds should contain block cells inside`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val mapper = GridToPuzzleMapper()
        val constraints = defaultConstraints()

        var generated = 0
        var withBlocks = 0
        for (seed in 0L until 30L) {
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
}
