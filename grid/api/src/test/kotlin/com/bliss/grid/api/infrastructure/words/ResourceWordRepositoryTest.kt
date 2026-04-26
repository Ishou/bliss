package com.bliss.grid.api.infrastructure.words

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/** Verifies the bundled fr.json loads and exposes the [WordRepository] surface. */
class ResourceWordRepositoryTest {
    private val repo = ResourceWordRepository.frenchFromClasspath()

    @Test
    fun `loads at least 80 words from the bundled resource`() {
        val all = (3..7).flatMap { repo.findByLength(it) }
        assertThat(all.size).isGreaterThanOrEqualTo(80)
    }

    @Test
    fun `every loaded word has an uppercase A-Z text and a non-empty clue`() {
        (3..7).forEach { length ->
            repo.findByLength(length).forEach { word ->
                assertThat(word.text.length).isEqualTo(length)
                assertThat(word.text.all { it in 'A'..'Z' }).isTrue()
                assertThat(word.definition.isNotBlank()).isTrue()
            }
        }
    }

    @Test
    fun `findByLengthAndPattern filters on the provided letter constraints`() {
        val candidates = repo.findByLengthAndPattern(4, mapOf(0 to 'C'))
        candidates.forEach { word ->
            assertThat(word.text.length).isEqualTo(4)
            assertThat(word.text[0]).isEqualTo('C')
        }
    }

    @Test
    fun `findByLength returns empty list when no entries match length`() {
        // The bundled word list is bounded to 3-7 letters.
        assertThat(repo.findByLength(50).isEmpty()).isTrue()
    }
}
