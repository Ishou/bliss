package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class LengthBiasTest {
    @Test
    fun `GridConstraints rejects longWordBias outside 0 to 1`() {
        assertThrows<IllegalArgumentException> {
            GridConstraints(width = 10, height = 10, longWordBias = -0.1)
        }
        assertThrows<IllegalArgumentException> {
            GridConstraints(width = 10, height = 10, longWordBias = 1.5)
        }
    }

    @Test
    fun `clampedBias caps the dial at the empirical sweet spot`() {
        assertThat(clampedBias(0.0)).isEqualTo(0.0)
        assertThat(clampedBias(0.4)).isEqualTo(0.4)
        assertThat(clampedBias(0.6)).isEqualTo(0.6)
        assertThat(clampedBias(0.9)).isEqualTo(0.6)
        assertThat(clampedBias(1.0)).isEqualTo(0.6)
    }

    @Test
    fun `lTargetFor produces longer caps at higher bias`() {
        val low = lTargetFor(0.0, lUseful = 18, minLen = 2)
        val high = lTargetFor(0.6, lUseful = 18, minLen = 2)
        assertThat(high).isGreaterThan(low)
        // Spec floor is max(minLen+1, 6) = 6 at bias 0.
        assertThat(low).isEqualTo(6)
    }

    @Test
    fun `densityFactor and whitenProbability move in opposite directions`() {
        assertThat(densityFactor(0.0)).isEqualTo(1.0)
        assertThat(densityFactor(0.6)).isEqualTo(1.0 - 0.35 * 0.6)
        assertThat(whitenProbability(0.0)).isEqualTo(0.4)
        assertThat(whitenProbability(0.6)).isEqualTo(0.4 + 0.45 * 0.6)
    }

    @Test
    fun `default-off seed shape is unchanged when both knobs are zero or false`() {
        val baseline =
            BlackCellLayout.seed(
                width = 7,
                height = 7,
                minLen = 2,
                lTarget = 9,
                lUseful = 15,
                blackRatio = 0.18,
                random = Random(42L),
            )
        val withDefaults =
            BlackCellLayout.seed(
                width = 7,
                height = 7,
                minLen = 2,
                lTarget = 9,
                lUseful = 15,
                blackRatio = 0.18,
                random = Random(42L),
                lMinGood = 2,
            )
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                assertThat(withDefaults.isBlack(r, c)).isEqualTo(baseline.isBlack(r, c))
            }
        }
    }

    @Test
    fun `bias raises mean slot length on seed-only black layouts`() {
        val repo = uniformCorpusRepo(perLength = 200, maxLen = 15)
        val lex = Lexicon(repo)
        val lUseful = lex.usefulLength
        var lowAvg = 0.0
        var highAvg = 0.0
        val trials = 25
        for (seed in 1L..trials.toLong()) {
            lowAvg += meanSlotLength(buildSlots(lex, 0.0, lUseful, Random(seed)))
            highAvg += meanSlotLength(buildSlots(lex, 0.4, lUseful, Random(seed)))
        }
        lowAvg /= trials
        highAvg /= trials
        // Spec §4.5.2 measured table: bias 0.0 ~ 3.9 letters, bias 0.4 ~ 4.4.
        // Use a conservative delta to avoid flakes on different corpora.
        assertThat(highAvg).isGreaterThanOrEqualTo(lowAvg + 0.2)
    }

    private fun buildSlots(
        lex: Lexicon,
        bias: Double,
        lUseful: Int,
        random: Random,
    ): SlotRegistry.Build {
        val lTarget = lTargetFor(bias, lUseful, 2)
        val cells =
            BlackCellLayout.seed(
                width = 12,
                height = 12,
                minLen = 2,
                lTarget = lTarget,
                lUseful = lUseful,
                blackRatio = GenerationKnobs.DEFAULT_BLACK_RATIO * densityFactor(bias),
                random = random,
                lMinGood = lMinGood(bias, lUseful, 2),
            )
        return SlotRegistry.build(cells, lex, 2) ?: error("layout invalid")
    }

    private fun meanSlotLength(build: SlotRegistry.Build): Double {
        val lengths = build.slots.map { it.length }
        return lengths.sum().toDouble() / lengths.size.coerceAtLeast(1)
    }

    private fun uniformCorpusRepo(
        perLength: Int,
        maxLen: Int,
    ): WordRepository =
        object : WordRepository {
            override fun findByLength(length: Int): List<Word> =
                if (length in 2..maxLen) {
                    (0 until perLength).map { i ->
                        val text =
                            buildString {
                                for (k in 0 until length) {
                                    append('A' + ((i * 7 + k * 3) % 26))
                                }
                            }
                        Word(text, "test-$length-$i")
                    }
                } else {
                    emptyList()
                }

            override fun findByLengthAndPattern(
                length: Int,
                pattern: Map<Int, Char>,
            ): List<Word> = findByLength(length).filter { w -> pattern.all { (p, c) -> w.text[p] == c } }

            override fun containsLemma(text: String): Boolean = true
        }
}
