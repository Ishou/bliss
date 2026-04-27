package com.bliss.grid.api.infrastructure.words

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/** Verifies the bundled words-fr.csv loads and exposes the WordRepository surface. */
class CsvWordRepositoryTest {
    private val repo = CsvWordRepository.frenchFromClasspath()

    @Test
    fun `loads at least 80 words with uppercase A-Z text and non-blank clues`() {
        val all = (3..7).flatMap { repo.findByLength(it) }
        assertThat(all.size).isGreaterThanOrEqualTo(80)
        all.forEach { word ->
            assertThat(word.text.length in 3..7).isTrue()
            assertThat(word.text.all { it in 'A'..'Z' }).isTrue()
            assertThat(word.definition.isNotBlank()).isTrue()
        }
    }

    @Test
    fun `findByLengthAndPattern filters on letter constraints`() {
        val matches = repo.findByLengthAndPattern(4, mapOf(0 to 'C'))
        assertThat(matches).isNotEmpty()
        matches.forEach { word ->
            assertThat(word.text.length).isEqualTo(4)
            assertThat(word.text[0]).isEqualTo('C')
        }
        // Bundled list is bounded to 3-7 letters; length 50 is empty.
        assertThat(repo.findByLength(50).isEmpty()).isTrue()
    }
}
