package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.validation.GridValidator
import org.junit.jupiter.api.Test

class GridGeneratorTest {

    private val validator = GridValidator()

    @Test
    fun `generates a small 3x3 grid that passes validation`() {
        val repository = InMemoryWordRepository(
            listOf(
                Word("OR", "x"),
                Word("OS", "y"),
                Word("AS", "z"),
                Word("RA", "w"),
            )
        )
        val generator = GridGenerator(repository)

        val grid = generator.generate(GridConstraints(width = 3, height = 3, targetDensity = 0.4))

        assertThat(grid).isNotNull()
        assertThat(validator.validate(grid!!)).isEmpty()
    }

    @Test
    fun `generates a 5x5 grid meeting target density`() {
        val generator = GridGenerator(InMemoryWordRepository.defaultFrench())

        val grid = generator.generate(GridConstraints(width = 5, height = 5, targetDensity = 0.5))

        assertThat(grid).isNotNull()
        val letterCount = grid!!.cells.values.count { it is com.bliss.grid.domain.model.LetterCell }
        assertThat(letterCount.toDouble() / 25.0).isGreaterThanOrEqualTo(0.5)
        assertThat(validator.validate(grid)).isEmpty()
    }

    @Test
    fun `returns null when no grid satisfying constraints exists`() {
        // Repository contains a single 5-letter word; grid is 3x3 — no word fits.
        val repository = InMemoryWordRepository(listOf(Word("ARBRE", "x")))
        val generator = GridGenerator(repository)

        val grid = generator.generate(GridConstraints(width = 3, height = 3, targetDensity = 0.6))

        assertThat(grid).isNull()
    }
}
