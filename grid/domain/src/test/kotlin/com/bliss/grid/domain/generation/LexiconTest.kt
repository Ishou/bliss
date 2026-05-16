package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LexiconTest {
    private fun mkWord(text: String): Word = Word(text = text, clues = listOf(WordClue("clue-$text")))

    @Test
    fun `count and initialMask reflect corpus size`() {
        val lex = Lexicon(ListWordRepository(listOf("CAT", "DOG", "BAT").map(::mkWord)))
        assertThat(lex.count(3)).isEqualTo(3)
        assertThat(lex.count(4)).isEqualTo(0)

        val mask = lex.initialMask(3)
        assertThat(lex.popcount(mask)).isEqualTo(3)
    }

    @Test
    fun `filterByLetterInPlace narrows to words with the letter at the position`() {
        val lex = Lexicon(ListWordRepository(listOf("CAT", "DOG", "BAT", "RAT").map(::mkWord)))
        val mask = lex.initialMask(3)
        // Position 0 = 'B' should leave only "BAT"
        val any = lex.filterByLetterInPlace(3, 0, 'B', mask)
        assertThat(any).isTrue()
        assertThat(lex.popcount(mask)).isEqualTo(1)
        val survivors = lex.iterIndices(mask).map { lex.wordAt(3, it).text }.toList()
        assertThat(survivors).containsExactlyInAnyOrder("BAT")
    }

    @Test
    fun `lettersAt returns the live 26-bit mask of possible letters`() {
        val lex = Lexicon(ListWordRepository(listOf("CAT", "DOG", "BAT", "RAT", "HAT").map(::mkWord)))
        val mask = lex.initialMask(3)
        val pos0 = lex.lettersAt(3, 0, mask) // C, D, B, R, H
        val expected = setOf('C', 'D', 'B', 'R', 'H').map { 1 shl (it.code - 'A'.code) }.reduce(Int::or)
        assertThat(pos0).isEqualTo(expected)

        val pos1 = lex.lettersAt(3, 1, mask) // A (CAT,BAT,RAT,HAT), O (DOG)
        val expected1 = setOf('A', 'O').map { 1 shl (it.code - 'A'.code) }.reduce(Int::or)
        assertThat(pos1).isEqualTo(expected1)
    }

    @Test
    fun `unionMaskForLetters combines per-letter bitmaps`() {
        val lex = Lexicon(ListWordRepository(listOf("CAT", "DOG", "BAT", "RAT", "HAT").map(::mkWord)))
        // Letters allowed at position 0: {B, C} ⇒ should match CAT and BAT
        val allowed = (1 shl ('B'.code - 'A'.code)) or (1 shl ('C'.code - 'A'.code))
        val mask = lex.unionMaskForLetters(3, 0, allowed)
        val survivors = lex.iterIndices(mask).map { lex.wordAt(3, it).text }.toSet()
        assertThat(survivors).isEqualTo(setOf("BAT", "CAT"))
    }

    @Test
    fun `pickIndex returns -1 on empty mask`() {
        val lex = Lexicon(ListWordRepository(listOf(mkWord("CAT"))))
        val empty = LongArray(lex.initialMask(3).size)
        assertThat(lex.pickIndex(empty, Random(0))).isEqualTo(-1)
    }

    @Test
    fun `pickIndex returns a valid set bit`() {
        val words = listOf("CAT", "DOG", "BAT", "RAT", "HAT", "EAT").map(::mkWord)
        val lex = Lexicon(ListWordRepository(words))
        val mask = lex.initialMask(3)
        val rng = Random(42)
        repeat(20) {
            val idx = lex.pickIndex(mask, rng)
            assertThat(idx).isGreaterThan(-1)
            // The bit must be set in mask
            val w = idx ushr 6
            val b = idx and 63
            assertThat((mask[w] ushr b) and 1L).isEqualTo(1L)
        }
    }

    @Test
    fun `usefulLength returns largest length with floor-meeting count`() {
        // Pin to exact boundary: length-5 at L_USEFUL_FLOOR passes, length-6 at L_USEFUL_FLOOR - 1 fails.
        val floor = GenerationKnobs.L_USEFUL_FLOOR
        val w5varied =
            (0 until floor).map { i ->
                val s = "ABCD" + ('A' + (i % 26))
                Word(text = s, clues = listOf(WordClue("c$i")))
            }
        val w6 =
            (0 until floor - 1).map { i ->
                val s = "ABCDE" + ('A' + (i % 26))
                Word(text = s, clues = listOf(WordClue("c$i")))
            }
        val lex = Lexicon(ListWordRepository(w5varied + w6))
        assertThat(lex.usefulLength).isEqualTo(5)
    }

    @Test
    fun `filterByLetterInPlace on empty intersection returns false`() {
        val lex = Lexicon(ListWordRepository(listOf("CAT").map(::mkWord)))
        val mask = lex.initialMask(3)
        // Position 0 with letter 'Z' yields no matches
        val any = lex.filterByLetterInPlace(3, 0, 'Z', mask)
        assertThat(any).isFalse()
        assertThat(lex.popcount(mask)).isEqualTo(0)
    }
}
