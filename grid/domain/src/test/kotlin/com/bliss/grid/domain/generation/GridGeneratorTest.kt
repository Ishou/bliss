package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.validation.GridValidator
import org.junit.jupiter.api.Test
import kotlin.random.Random

class GridGeneratorTest {
    private val validator = GridValidator()

    @Test
    fun `generates a small 3x3 grid that passes validation`() {
        val repository =
            ListWordRepository(
                listOf(
                    Word("OR", "x"),
                    Word("OS", "y"),
                    Word("AS", "z"),
                    Word("RA", "w"),
                ),
            )
        val generator = GridGenerator(repository)

        val grid = generator.generate(GridConstraints(width = 3, height = 3, targetDensity = 0.4, enforceInterlocking = false))

        assertThat(grid).isNotNull()
        assertThat(validator.validate(grid!!)).isEmpty()
    }

    @Test
    fun `generates a 5x5 grid meeting target density`() {
        val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

        val grid = generator.generate(GridConstraints(width = 5, height = 5, targetDensity = 0.5, enforceInterlocking = false))

        assertThat(grid).isNotNull()
        val letterCount = grid!!.cells.values.count { it is LetterCell }
        assertThat(letterCount.toDouble() / 25.0).isGreaterThanOrEqualTo(0.5)
        assertThat(validator.validate(grid)).isEmpty()
    }

    @Test
    fun `returns null when no grid satisfying constraints exists`() {
        val repository = ListWordRepository(listOf(Word("ARBRE", "x")))
        val generator = GridGenerator(repository)

        val grid = generator.generate(GridConstraints(width = 3, height = 3, targetDensity = 0.6, enforceInterlocking = false))

        assertThat(grid).isNull()
    }

    @Test
    fun `returns null when maxAttempts is exhausted`() {
        val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

        val grid =
            generator.generate(
                GridConstraints(width = 5, height = 5, targetDensity = 0.9, enforceInterlocking = false, maxAttempts = 5),
            )

        assertThat(grid).isNull()
    }

    @Test
    fun `different random seeds produce different grids for the same constraints`() {
        val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))
        val constraints = GridConstraints(width = 5, height = 5, targetDensity = 0.4, enforceInterlocking = false)

        val grid1 = generator.generate(constraints, Random(1L))
        val grid2 = generator.generate(constraints, Random(2L))

        assertThat(grid1).isNotNull()
        assertThat(grid2).isNotNull()
        assertThat(grid1!!.cells).isNotEqualTo(grid2!!.cells)
    }

    @Test
    fun `returns null when wordlist is too small to satisfy density`() {
        val tinyList =
            listOf(
                Word("OR", "metal"),
                Word("OS", "anatomie"),
                Word("AS", "carte"),
                Word("DU", "article"),
                Word("ET", "conjonction"),
            )
        val generator = GridGenerator(ListWordRepository(tinyList))

        val grid =
            generator.generate(
                GridConstraints(width = 10, height = 10, targetDensity = 0.5, enforceInterlocking = false, maxAttempts = 2_000),
                Random(42L),
            )

        assertThat(grid).isNull()
    }

    @Test
    fun `generated grid passes interlocking validation when enforced`() {
        // SMALL_FRENCH_WORDS cannot form word squares (too few words per length).
        // A 7×7 grid with maxWordLen=3 produces [2,3]/[3,2] column partitions — non-full-width
        // blocks use RIGHT/DOWN directions which avoid the clue/letter overlap that makes the
        // single-block 5×5 case always return null.
        val generator = GridGenerator(ListWordRepository(WORD_SQUARE_7X7))

        val grid =
            generator.generate(
                GridConstraints(width = 7, height = 7, enforceInterlocking = true),
            )

        assertThat(grid).isNotNull()
        val uncrossed = GridValidator.uncrossedCells(grid!!)
        assertThat(uncrossed).isEmpty()
    }

    @Test
    fun `top-left corner emits a stacked DOWN_RIGHT + RIGHT_DOWN clue when extensions exist`() {
        // 6×6 grid with words of length 2 and 3 only. Interior is 5, the only
        // valid partition is [2, 2] (so the top-left block is always 2×2 and
        // never full-width/height). The 3-letter "Z?" extensions match every
        // candidate row-1 / col-1 word, letting the generator grow both by
        // one letter and emit a stacked clue at (0,0).
        val generator = GridGenerator(ListWordRepository(WORD_SQUARE_6X6_WITH_CORNER))

        val grid =
            generator.generate(
                GridConstraints(width = 6, height = 6, enforceInterlocking = true),
                Random(7L),
            )

        assertThat(grid).isNotNull()
        val origin = Position(Row(0), Column(0))
        val cluesAtOrigin = grid!!.placements.filter { it.cluePosition == origin }.map { it.direction }.toSet()
        assertThat(cluesAtOrigin.contains(Direction.DOWN_RIGHT)).isTrue()
        assertThat(cluesAtOrigin.contains(Direction.RIGHT_DOWN)).isTrue()
        assertThat(GridValidator.uncrossedCells(grid)).isEmpty()
    }
}

/**
 * Minimal word set crafted to produce a solvable 7×7 interlocked grid.
 * The 7×7 interior (6 cells) partitions as [2,3] or [3,2], giving four non-full-width
 * blocks (2×2, 2×3, 3×2, 3×3) where RIGHT/DOWN directions avoid clue/letter overlap.
 * Each block is a provable word square: rows AB+CD→cols AC,BD; EFG+HIJ→EH,FI,GJ;
 * KL+MN+OP→KMO,LNP; QRS+TUV+WXY→QTW,RUX,SVY.
 */
internal val WORD_SQUARE_7X7: List<Word> =
    listOf(
        Word("AB", "test"),
        Word("CD", "test"),
        Word("AC", "test"),
        Word("BD", "test"),
        Word("EH", "test"),
        Word("FI", "test"),
        Word("GJ", "test"),
        Word("KL", "test"),
        Word("MN", "test"),
        Word("OP", "test"),
        Word("EFG", "test"),
        Word("HIJ", "test"),
        Word("KMO", "test"),
        Word("LNP", "test"),
        Word("QRS", "test"),
        Word("TUV", "test"),
        Word("WXY", "test"),
        Word("QTW", "test"),
        Word("RUX", "test"),
        Word("SVY", "test"),
    )

/**
 * 6×6 fixture: four 2×2 word squares (one per block) plus a set of 3-letter
 * "Z?" extensions whose suffix matches every candidate row-1 / col-1 word in
 * the top-left block. Drives the corner-extension code path: the generator
 * grows the row-1 horizontal and col-1 vertical by one letter and stacks
 * DOWN_RIGHT + RIGHT_DOWN at (0,0).
 */
internal val WORD_SQUARE_6X6_WITH_CORNER: List<Word> =
    listOf(
        // Top-left block (interior 0..1, 0..1): rows AB+CD, cols AC+BD
        Word("AB", "test"),
        Word("CD", "test"),
        Word("AC", "test"),
        Word("BD", "test"),
        // Top-right block (interior 0..1, 3..4): rows EF+GH, cols EG+FH
        Word("EF", "test"),
        Word("GH", "test"),
        Word("EG", "test"),
        Word("FH", "test"),
        // Bottom-left block (interior 3..4, 0..1): rows IJ+KL, cols IK+JL
        Word("IJ", "test"),
        Word("KL", "test"),
        Word("IK", "test"),
        Word("JL", "test"),
        // Bottom-right block (interior 3..4, 3..4): rows MN+OP, cols MO+NP
        Word("MN", "test"),
        Word("OP", "test"),
        Word("MO", "test"),
        Word("NP", "test"),
        // 3-letter extensions for the top-left corner — one per candidate
        // row-1 / col-1 word so the corner-extension lookup always succeeds.
        Word("ZAB", "ext"),
        Word("ZCD", "ext"),
        Word("ZAC", "ext"),
        Word("ZBD", "ext"),
    )

internal val SMALL_FRENCH_WORDS: List<Word> =
    listOf(
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
