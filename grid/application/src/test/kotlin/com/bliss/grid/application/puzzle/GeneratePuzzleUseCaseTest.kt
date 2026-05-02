package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test

class GeneratePuzzleUseCaseTest {
    @Test
    fun `returns null when word repository is empty and all attempts fail`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        assertThat(useCase.execute()).isNull()
    }

    @Test
    fun `returns a grid when word repository provides sufficient vocabulary`() {
        // AlwaysMatchingRepository synthesizes 26 distinct words for any requested
        // pattern, giving the CSP solver enough candidates to always find a consistent
        // assignment without requiring a real corpus.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        assertThat(useCase.execute()).isNotNull()
    }

    @Test
    fun `returns null after exhausting maxAttempts with empty vocabulary`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 1,
            )
        assertThat(useCase.execute()).isNull()
    }

    @Test
    fun `caller-supplied width and height override the defaults`() {
        // The lobby owner picks a 7x7 grid; the use case must produce a grid of
        // exactly that size, not the 5x5 default carried by the constructor.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        val grid = useCase.execute(width = 7, height = 7)
        assertThat(grid).isNotNull()
        assertThat(grid!!.width).isEqualTo(7)
        assertThat(grid.height).isEqualTo(7)
    }

    @Test
    fun `omitted dimensions fall back to the configured defaults`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 6, height = 6),
            )
        val grid = useCase.execute()
        assertThat(grid).isNotNull()
        assertThat(grid!!.width).isEqualTo(6)
        assertThat(grid.height).isEqualTo(6)
    }
}

private object EmptyWordRepository : WordRepository {
    override fun findByLength(length: Int): List<Word> = emptyList()

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> = emptyList()
}

/**
 * Synthesizes diverse words for any requested pattern so the backtracking CSP solver
 * always has enough distinct candidates to find a consistent grid assignment.
 *
 * For each of 26 variants (n=0..25) the unconstrained positions are filled with
 * rotationally-shifted letters: position k gets 'A' + (n + k*7) mod 26 (step 7 is
 * coprime to 26, spreading letters evenly across the alphabet). This avoids the
 * all-same-letter pattern ("AAAA", "BBBB") that causes the CSP to saturate
 * uniqueness quickly and backtrack indefinitely.
 */
private object AlwaysMatchingRepository : WordRepository {
    override fun findByLength(length: Int): List<Word> = candidates(length, emptyMap())

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> = candidates(length, pattern)

    private fun candidates(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> {
        val unconstrained = (0 until length).filter { it !in pattern }
        return (0..25)
            .map { n ->
                val chars = CharArray(length) { i -> pattern[i] ?: 'A' }
                unconstrained.forEachIndexed { idx, pos -> chars[pos] = 'A' + (n + idx * 7) % 26 }
                Word(String(chars), "test")
            }.distinctBy { it.text }
    }
}
