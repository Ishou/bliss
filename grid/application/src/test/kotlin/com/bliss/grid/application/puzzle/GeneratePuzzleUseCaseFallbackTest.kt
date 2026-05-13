package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.generation.GenerationMetrics
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test
import kotlin.random.Random

// Verifies the strict-BLACK fallback path in GeneratePuzzleUseCase.
class GeneratePuzzleUseCaseFallbackTest {
    @Test
    fun `relaxed retry runs once when the strict pass exhausts its budget`() {
        val relaxedGrid = Grid.fromPlacements(width = 5, height = 5, placements = emptyList())
        val recorder = RecordingGenerator(strictResult = null, relaxedResult = relaxedGrid)
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyRepo,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 2,
            )
        useCase.generator = recorder

        val outcome = useCase.executeWithOutcome()

        // The fallback path was taken (strict invoked maxAttempts times, then
        // relaxed invoked at least once and returned the relaxed grid).
        assertThat(outcome.grid).isNotNull()
        assertThat(outcome.grid!!).isSameInstanceAs(relaxedGrid)
        // Strict path fully exhausted before fallback fired.
        assertThat(recorder.strictInvocations).isEqualTo(2)
        // Relaxed path succeeded on its first attempt.
        assertThat(recorder.relaxedInvocations).isEqualTo(1)
        // Both strict and relaxed flags were observed in order.
        assertThat(recorder.observedFlags).containsExactly(true, true, false)
    }

    @Test
    fun `strict success short-circuits the relaxed retry`() {
        val strictGrid = Grid.fromPlacements(width = 5, height = 5, placements = emptyList())
        val recorder = RecordingGenerator(strictResult = strictGrid, relaxedResult = null)
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyRepo,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 3,
            )
        useCase.generator = recorder

        val outcome = useCase.executeWithOutcome()

        assertThat(outcome.grid).isNotNull()
        assertThat(outcome.grid!!).isSameInstanceAs(strictGrid)
        assertThat(recorder.strictInvocations).isEqualTo(1)
        assertThat(recorder.relaxedInvocations).isEqualTo(0)
    }

    private class RecordingGenerator(
        private val strictResult: Grid?,
        private val relaxedResult: Grid?,
    ) : PuzzleGridGenerator {
        var strictInvocations = 0
            private set
        var relaxedInvocations = 0
            private set
        val observedFlags = mutableListOf<Boolean>()

        override fun generate(
            constraints: GridConstraints,
            random: Random,
            metrics: GenerationMetrics,
            timeoutMs: Long,
            cooldownPolicy: ClueCooldownPolicy,
            strictFunctionalBlackCells: Boolean,
        ): Grid? {
            observedFlags += strictFunctionalBlackCells
            return if (strictFunctionalBlackCells) {
                strictInvocations++
                strictResult
            } else {
                relaxedInvocations++
                relaxedResult
            }
        }
    }

    private object EmptyRepo : WordRepository {
        override fun findByLength(length: Int): List<Word> = emptyList()

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> = emptyList()

        override fun containsLemma(text: String): Boolean = false
    }
}
