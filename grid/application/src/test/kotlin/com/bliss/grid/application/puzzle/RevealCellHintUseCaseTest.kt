package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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

class RevealCellHintUseCaseTest {
    @Test
    fun `Granted echoes the canonical letter and decrements remaining budget`() {
        val (puzzleId, sessionId) = ids()
        // Sample puzzle: "OR" at clue (0,0) going RIGHT → letters at (0,1)='O', (0,2)='R'.
        val outcome =
            RevealCellHintUseCase(fakePuzzleStore(puzzleId), fakeHintUsage())
                .execute(puzzleId, sessionId, row = 0, column = 1)

        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.Granted::class)
        val granted = outcome as RevealCellHintOutcome.Granted
        assertThat(granted.row).isEqualTo(0)
        assertThat(granted.column).isEqualTo(1)
        assertThat(granted.letter).isEqualTo('O')
        assertThat(granted.hintsRemaining).isEqualTo(2)
    }

    @Test
    fun `PuzzleNotFound when store has no entry`() {
        val (puzzleId, sessionId) = ids()
        val outcome =
            RevealCellHintUseCase(fakePuzzleStore(), fakeHintUsage())
                .execute(puzzleId, sessionId, row = 0, column = 1)
        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.PuzzleNotFound::class)
    }

    @Test
    fun `InvalidCoord when row is out of bounds`() {
        val (puzzleId, sessionId) = ids()
        val budget = fakeHintUsage()
        val outcome =
            RevealCellHintUseCase(fakePuzzleStore(puzzleId), budget)
                .execute(puzzleId, sessionId, row = 99, column = 0)
        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.InvalidCoord::class)
        // Budget MUST NOT decrement on a coordinate validation failure.
        assertThat(budget.peek(puzzleId, sessionId)).isEqualTo(0)
    }

    @Test
    fun `InvalidCoord when coordinate points at a clue cell`() {
        val (puzzleId, sessionId) = ids()
        val budget = fakeHintUsage()
        // (0, 0) is the clue cell for the sample puzzle's "OR" placement.
        val outcome =
            RevealCellHintUseCase(fakePuzzleStore(puzzleId), budget)
                .execute(puzzleId, sessionId, row = 0, column = 0)
        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.InvalidCoord::class)
        assertThat(budget.peek(puzzleId, sessionId)).isEqualTo(0)
    }

    @Test
    fun `InvalidCoord when coordinate points at an empty cell`() {
        val (puzzleId, sessionId) = ids()
        val budget = fakeHintUsage()
        // Sample 3x3 grid only fills row 0; (1, 1) has no cell entry.
        val outcome =
            RevealCellHintUseCase(fakePuzzleStore(puzzleId), budget)
                .execute(puzzleId, sessionId, row = 1, column = 1)
        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.InvalidCoord::class)
        assertThat(budget.peek(puzzleId, sessionId)).isEqualTo(0)
    }

    @Test
    fun `BudgetExhausted on the 4th call with default cap of 3`() {
        val (puzzleId, sessionId) = ids()
        val useCase = RevealCellHintUseCase(fakePuzzleStore(puzzleId), fakeHintUsage())
        repeat(3) { useCase.execute(puzzleId, sessionId, row = 0, column = 1) }
        val outcome = useCase.execute(puzzleId, sessionId, row = 0, column = 1)
        assertThat(outcome).isInstanceOf(RevealCellHintOutcome.BudgetExhausted::class)
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

    /**
     * Test double exposing a peek hatch so coord-rejection tests can assert
     * the budget did NOT advance. Production `HintUsageRepository` has no
     * such method by design (the route doesn't need a non-spending read).
     */
    private interface PeekableHintUsage : HintUsageRepository {
        fun peek(
            puzzleId: UUID,
            sessionId: UUID,
        ): Int
    }

    private fun fakeHintUsage(): PeekableHintUsage {
        val counters = ConcurrentHashMap<Pair<UUID, UUID>, AtomicInteger>()
        return object : PeekableHintUsage {
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

            override fun deleteBySession(sessionId: UUID): Int {
                val keys = counters.keys.filter { it.second == sessionId }
                keys.forEach { counters.remove(it) }
                return keys.size
            }

            override fun peek(
                puzzleId: UUID,
                sessionId: UUID,
            ): Int = counters[puzzleId to sessionId]?.get() ?: 0
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
