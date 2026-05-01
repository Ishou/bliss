package com.bliss.grid.api.csv

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    @Test
    fun `fromClasspath throws when resource is missing`() {
        assertThrows<IllegalStateException> {
            CsvWordRepository.fromClasspath("/words/no-such-file.csv")
        }
    }

    @Test
    fun `fromClasspath throws on header mismatch`() {
        assertThrows<IllegalArgumentException> {
            CsvWordRepository.fromClasspath("/words/bad-header-test.csv")
        }
    }

    @Test
    fun `fromClasspath throws when a row has a blank clue`() {
        assertThrows<IllegalArgumentException> {
            CsvWordRepository.fromClasspath("/words/blank-clue-test.csv")
        }
    }

    @Test
    fun `lemma column when present round-trips into Word lemma (folded to ASCII)`() {
        val repo = CsvWordRepository.fromClasspath("/words/lemma-column-test.csv")
        // "aimera" — inflected form of "aimer". Lemma is folded to A-Z uppercase
        // for dedup parity with Word.text.
        val match = repo.findByLength(6).single { it.text == "AIMERA" }
        assertThat(match.lemma).isEqualTo("AIMER")
    }

    @Test
    fun `lemma column when absent defaults to the word itself (legacy CSV)`() {
        val legacyRepo = CsvWordRepository.fromClasspath("/words/legacy-8-column-test.csv")
        val word = legacyRepo.findByLength(4).single { it.text == "CHAT" }
        assertThat(word.lemma).isEqualTo(word.text)
    }

    @Test
    fun `bundled french CSV carries real lemma data (non-trivial inflections)`() {
        // The production export populates lemma for every row. Most short words
        // are themselves lemmas (lemma == text), but a meaningful fraction of
        // longer words are inflections whose lemma differs — pin that fraction
        // is non-zero so a regression to the legacy `lemma=word` fallback is
        // caught loudly.
        val longerWords = (4..7).flatMap { repo.findByLength(it) }
        val inflected = longerWords.count { it.lemma != it.text }
        assertThat(inflected).isGreaterThanOrEqualTo(1)
    }
}
