package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.validation.GridValidator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

@OptIn(ExperimentalKotest::class)
class GridGeneratorPropertyTest {

    private val validator = GridValidator()
    private val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

    @Test
    fun `every generated grid passes validation`() {
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 30),
                Arb.int(3..6),
                Arb.int(3..6),
                Arb.numericDouble(0.2, 0.6),
            ) { width, height, density ->
                val grid = generator.generate(GridConstraints(width, height, targetDensity = density))
                if (grid != null) {
                    val violations = validator.validate(grid)
                    check(violations.isEmpty()) { "violations=$violations for ${width}x$height density=$density" }
                }
            }
        }
    }

    @Test
    fun `every letter cell in a generated grid belongs to at least one placement`() {
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(3..6),
                Arb.int(3..6),
                Arb.numericDouble(0.2, 0.5),
            ) { width, height, density ->
                val grid = generator.generate(GridConstraints(width, height, targetDensity = density))
                if (grid != null) {
                    val coveredPositions = grid.placements
                        .flatMap { it.letterPositions().map { (pos, _) -> pos } }
                        .toSet()
                    val orphans = grid.cells
                        .filter { (_, cell) -> cell is LetterCell }
                        .map { it.key }
                        .filterNot { it in coveredPositions }
                    check(orphans.isEmpty()) { "orphans=$orphans for ${width}x$height density=$density" }
                }
            }
        }
    }
}
