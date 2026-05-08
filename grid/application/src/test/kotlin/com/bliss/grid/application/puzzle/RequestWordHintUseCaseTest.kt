package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RequestWordHintUseCaseTest {
    @Test
    fun `Granted carries normalized word lowercase NFC`() {
        val (puzzleId, sessionId) = ids()
        val store = fakePuzzleStore(puzzleId)
        val budget = fakeHintUsage()
        val corpus = fakeWordRepo(known = setOf("FORET"))
        val outcome = RequestWordHintUseCase(store, budget, corpus).execute(puzzleId, sessionId, "FORÊT")
        assertThat(outcome).isInstanceOf(WordHintOutcome.Granted::class)
        val granted = outcome as WordHintOutcome.Granted
        assertThat(granted.word).isEqualTo("forêt")
        assertThat(granted.exists).isEqualTo(true)
        assertThat(granted.hintsRemaining).isEqualTo(2)
    }

    @Test
    fun `unknown word returns Granted with exists=false but still spends a hint`() {
        val (puzzleId, sessionId) = ids()
        val store = fakePuzzleStore(puzzleId)
        val budget = fakeHintUsage()
        val corpus = fakeWordRepo(known = emptySet())
        val outcome = RequestWordHintUseCase(store, budget, corpus).execute(puzzleId, sessionId, "fakeword")
        val granted = outcome as WordHintOutcome.Granted
        assertThat(granted.exists).isEqualTo(false)
        assertThat(granted.hintsRemaining).isEqualTo(2)
    }

    @Test
    fun `PuzzleNotFound when store has no entry`() {
        val (puzzleId, sessionId) = ids()
        val store = fakePuzzleStore() // empty
        val budget = fakeHintUsage()
        val corpus = fakeWordRepo()
        val outcome = RequestWordHintUseCase(store, budget, corpus).execute(puzzleId, sessionId, "chien")
        assertThat(outcome).isInstanceOf(WordHintOutcome.PuzzleNotFound::class)
    }

    @Test
    fun `BudgetExhausted on the 4th call with default cap of 3`() {
        val (puzzleId, sessionId) = ids()
        val useCase = RequestWordHintUseCase(fakePuzzleStore(puzzleId), fakeHintUsage(), fakeWordRepo())
        repeat(3) { useCase.execute(puzzleId, sessionId, "chien") }
        val outcome = useCase.execute(puzzleId, sessionId, "chien")
        assertThat(outcome).isInstanceOf(WordHintOutcome.BudgetExhausted::class)
    }

    @Test
    fun `InvalidWord when length is below 2`() {
        val (puzzleId, sessionId) = ids()
        val outcome =
            RequestWordHintUseCase(fakePuzzleStore(puzzleId), fakeHintUsage(), fakeWordRepo())
                .execute(puzzleId, sessionId, "a")
        assertThat(outcome).isInstanceOf(WordHintOutcome.InvalidWord::class)
    }

    private fun ids(): Pair<UUID, UUID> = UUID.randomUUID() to UUID.randomUUID()

    private fun fakePuzzleStore(seedId: UUID? = null): PuzzleRepository {
        val store = ConcurrentHashMap<UUID, StoredPuzzle>()
        if (seedId != null) store[seedId] = sampleStoredPuzzle()
        return object : PuzzleRepository {
            override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

            override fun getOrCompute(
                puzzleId: UUID,
                factory: () -> StoredPuzzle?,
            ): StoredPuzzle? = store[puzzleId] ?: factory()?.also { store[puzzleId] = it }
        }
    }

    private fun fakeHintUsage(): HintUsageRepository {
        val counters = ConcurrentHashMap<Pair<UUID, UUID>, AtomicInteger>()
        return object : HintUsageRepository {
            override fun trySpend(
                puzzleId: UUID,
                sessionId: UUID,
                hintsAllowed: Int,
            ): Int? {
                val counter = counters.computeIfAbsent(puzzleId to sessionId) { AtomicInteger(0) }
                while (true) {
                    val current = counter.get()
                    if (current >= hintsAllowed) return null
                    if (counter.compareAndSet(current, current + 1)) return current + 1
                }
            }
        }
    }

    private fun fakeWordRepo(known: Set<String> = emptySet()): WordRepository =
        object : WordRepository {
            override fun findByLength(length: Int) = emptyList<Word>()

            override fun findByLengthAndPattern(
                length: Int,
                pattern: Map<Int, Char>,
            ) = emptyList<Word>()

            override fun containsLemma(text: String): Boolean {
                // Simulate the production CsvWordRepository's foldToAscii:
                // strip diacritics, expand French ligatures, uppercase.
                val folded =
                    java.text.Normalizer
                        .normalize(text, java.text.Normalizer.Form.NFD)
                        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                        .replace("œ", "oe")
                        .replace("Œ", "OE")
                        .replace("æ", "ae")
                        .replace("Æ", "AE")
                        .uppercase()
                return folded in known
            }
        }

    private fun sampleStoredPuzzle(): StoredPuzzle =
        StoredPuzzle(
            grid =
                Grid.fromPlacements(
                    width = 3,
                    height = 3,
                    placements =
                        listOf(
                            WordPlacement(
                                Word(text = "OR", definition = "metal"),
                                Position(Row(0), Column(0)),
                                Direction.RIGHT,
                            ),
                        ),
                ),
            title = "T",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-04-24T15:30:00Z"),
        )
}
