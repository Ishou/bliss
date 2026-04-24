package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.validation.GridValidator
import org.junit.jupiter.api.Test

class GridGeneratorTest {

    private val validator = GridValidator()

    @Test
    fun `generates a small 3x3 grid that passes validation`() {
        val repository = ListWordRepository(
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
        val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

        val grid = generator.generate(GridConstraints(width = 5, height = 5, targetDensity = 0.5))

        assertThat(grid).isNotNull()
        val letterCount = grid!!.cells.values.count { it is LetterCell }
        assertThat(letterCount.toDouble() / 25.0).isGreaterThanOrEqualTo(0.5)
        assertThat(validator.validate(grid)).isEmpty()
    }

    @Test
    fun `returns null when no grid satisfying constraints exists`() {
        val repository = ListWordRepository(listOf(Word("ARBRE", "x")))
        val generator = GridGenerator(repository)

        val grid = generator.generate(GridConstraints(width = 3, height = 3, targetDensity = 0.6))

        assertThat(grid).isNull()
    }

    @Test
    fun `returns null when maxAttempts is exhausted`() {
        val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

        val grid = generator.generate(
            GridConstraints(width = 5, height = 5, targetDensity = 0.9, maxAttempts = 5)
        )

        assertThat(grid).isNull()
    }
}

internal val SMALL_FRENCH_WORDS: List<Word> = listOf(
    Word("OR", "metal precieux"),
    Word("OS", "anatomie"),
    Word("AS", "carte a jouer"),
    Word("DU", "article contracte"),
    Word("ET", "conjonction"),
    Word("UN", "article indefini"),
    Word("ON", "pronom"),
    Word("AIR", "atmosphere"),
    Word("EAU", "liquide vital"),
    Word("FEU", "combustion"),
    Word("RUE", "voie urbaine"),
    Word("SOL", "surface au sol"),
    Word("VIE", "existence"),
    Word("AMI", "compagnon"),
    Word("FIL", "ligne fine"),
    Word("BOL", "recipient"),
    Word("MUR", "paroi"),
    Word("NEZ", "organe olfactif"),
    Word("CHAT", "felin domestique"),
    Word("MAIN", "extremite du bras"),
    Word("ROSE", "fleur a epines"),
    Word("VENT", "air en mouvement"),
    Word("CHIEN", "canide domestique"),
    Word("LIVRE", "ouvrage relie"),
    Word("TABLE", "meuble plat"),
    Word("ARBRE", "vegetal a tronc"),
)
