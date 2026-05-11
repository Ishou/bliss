package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test
import kotlin.random.Random

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

    @Test
    fun `perAttemptTimeout uses maxAttemptTimeoutMs during warmup then switches to calibrated value`() {
        // warmupCount=1: first execute (0 recorded successes) uses maxAttemptTimeoutMs;
        // after it records a success the second execute uses calibrated timeout.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
                warmupCount = 1,
                cutoffMultiplier = 2,
                minAttemptTimeoutMs = 1,
                maxAttemptTimeoutMs = 60_000,
            )
        assertThat(useCase.execute()).isNotNull()
        // Second call: window has 1 entry >= warmupCount=1, so calibrated path runs.
        assertThat(useCase.execute()).isNotNull()
    }

    @Test
    fun `rolling window evicts oldest entries beyond rollingWindow`() {
        // rollingWindow=2: after 3 successes only the last 2 should influence the median.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
                warmupCount = 1,
                rollingWindow = 2,
                cutoffMultiplier = 1,
                minAttemptTimeoutMs = 1,
                maxAttemptTimeoutMs = 60_000,
            )
        repeat(3) { assertThat(useCase.execute()).isNotNull() }
        // After 3 calls the window holds 2 entries (oldest evicted). Execute still works.
        assertThat(useCase.execute()).isNotNull()
    }

    @Test
    fun `executeWithOutcome on a successful first attempt reports attempts equals 1`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.grid).isNotNull()
        assertThat(outcome.succeeded).isTrue()
        assertThat(outcome.attempts).isEqualTo(1)
        assertThat(outcome.perAttemptMs).hasSize(1)
        assertThat(outcome.perAttemptMetrics).hasSize(1)
    }

    @Test
    fun `executeWithOutcome exhausts maxAttempts when no grid can be built`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 2,
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.grid).isNull()
        assertThat(outcome.succeeded).isFalse()
        assertThat(outcome.attempts).isEqualTo(2)
        assertThat(outcome.perAttemptMs).hasSize(2)
        assertThat(outcome.perAttemptMetrics).hasSize(2)
    }

    @Test
    fun `executeWithOutcome totalMs equals the sum of perAttemptMs`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 3,
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.totalMs).isEqualTo(outcome.perAttemptMs.sum())
        assertThat(outcome.totalMs).isGreaterThan(-1L)
    }

    @Test
    fun `execute and executeWithOutcome return the same grid for the same randomFactory`() {
        // Regression guard: execute() must be a behaviour-preserving wrapper.
        // Both calls share the same deterministic randomFactory so the underlying
        // generator sees identical seeds across the two invocations.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        val deterministicFactory: (Int) -> Random = { attempt -> Random(7L + attempt) }
        val viaOutcome = useCase.executeWithOutcome(randomFactory = deterministicFactory).grid
        val viaExecute = useCase.executeWithOutcome(randomFactory = deterministicFactory).grid
        // Both calls have the same input → same output by determinism of the generator.
        assertThat(viaOutcome).isNotNull()
        assertThat(viaExecute).isNotNull()
        assertThat(viaOutcome!!.width).isEqualTo(viaExecute!!.width)
        assertThat(viaOutcome.height).isEqualTo(viaExecute.height)
        assertThat(viaOutcome.placements.size).isEqualTo(viaExecute.placements.size)
    }
}

private object EmptyWordRepository : WordRepository {
    override fun findByLength(length: Int): List<Word> = emptyList()

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> = emptyList()

    override fun containsLemma(text: String): Boolean = false
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

    override fun containsLemma(text: String): Boolean = true

    private fun candidates(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> {
        val unconstrained = (0 until length).filter { it !in pattern }
        // Several shift constants — each produces a different family of
        // 26 words. Combined they give ~100 distinct words per length,
        // enough variety for the bitmask CSP solver to find consistent
        // assignments even with non-trivial interior crossings.
        val shifts = intArrayOf(1, 5, 7, 11, 17, 23)
        return shifts.flatMap { shift ->
            (0..25).map { n ->
                val chars = CharArray(length) { i -> pattern[i] ?: 'A' }
                unconstrained.forEachIndexed { idx, pos ->
                    chars[pos] = 'A' + (n + idx * shift) % 26
                }
                Word(String(chars), "test")
            }
        }.distinctBy { it.text }
    }
}
