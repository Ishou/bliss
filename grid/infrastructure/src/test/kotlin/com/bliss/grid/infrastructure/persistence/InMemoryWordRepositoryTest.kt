package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test

class InMemoryWordRepositoryTest {

    private val repo = InMemoryWordRepository(
        listOf(
            Word("OR", "metal precieux"),
            Word("OS", "anatomie"),
            Word("AS", "carte a jouer"),
            Word("CHAT", "felin"),
            Word("CHIEN", "canide"),
            Word("CHOSE", "objet"),
        )
    )

    @Test
    fun `findByLength returns words of the requested length`() {
        assertThat(repo.findByLength(2).map { it.text })
            .containsExactlyInAnyOrder("OR", "OS", "AS")
    }

    @Test
    fun `findByLength returns empty list when no words match`() {
        assertThat(repo.findByLength(10)).isEmpty()
    }

    @Test
    fun `findByLengthAndPattern returns words matching all positions`() {
        assertThat(repo.findByLengthAndPattern(4, mapOf(0 to 'C', 2 to 'A')).map { it.text })
            .containsExactlyInAnyOrder("CHAT")
    }

    @Test
    fun `findByLengthAndPattern with empty pattern is equivalent to findByLength`() {
        assertThat(repo.findByLengthAndPattern(2, emptyMap()).map { it.text })
            .containsExactlyInAnyOrder("OR", "OS", "AS")
    }

    @Test
    fun `findByLengthAndPattern returns empty when nothing matches`() {
        assertThat(repo.findByLengthAndPattern(4, mapOf(0 to 'Z'))).isEmpty()
    }
}
