package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EnsureUpcomingDailiesUseCaseTest {
    private val today: LocalDate = LocalDate.of(2026, 5, 13)
    private val selector = DailyPuzzleSelector()

    @Test
    fun `all seven days already persisted skips generation entirely`() {
        val repo = TrackingPuzzleRepository()
        for (offset in 0 until 7) {
            val date = today.plusDays(offset.toLong())
            repo.put(selector.puzzleIdForDate(date), newStoredPuzzle())
        }
        val port = RecordingPort(grids = { _ -> successfulGrid() })
        val useCase = newUseCase(repo, port)

        val summary = useCase.execute(today)

        assertThat(summary.persistedDates).hasSize(7)
        assertThat(summary.generatedDates).isEmpty()
        assertThat(summary.failedDates).isEmpty()
        assertThat(summary.skippedDates).isEmpty()
        assertThat(port.calls).isEmpty()
        assertThat(repo.getOrComputeCalls).isEmpty()
    }

    @Test
    fun `seven days needing generation succeed on first attempt and persist in date order`() {
        val repo = TrackingPuzzleRepository()
        val port = RecordingPort(grids = { _ -> successfulGrid() })
        val useCase = newUseCase(repo, port)

        val summary = useCase.execute(today)

        val expectedDates = (0L until 7L).map { today.plusDays(it) }
        assertThat(summary.generatedDates).containsExactly(*expectedDates.toTypedArray())
        assertThat(summary.failedDates).isEmpty()
        assertThat(summary.skippedDates).isEmpty()
        assertThat(summary.persistedDates).isEmpty()
        val seenSeeds = port.calls.map { it.seed }
        val expectedSeeds = expectedDates.map { it.toEpochDay() * 1_000_000_000L }
        assertThat(seenSeeds).containsExactly(*expectedSeeds.toTypedArray())
        val writtenIds = repo.getOrComputeCalls.toList()
        val expectedIds = expectedDates.map { selector.puzzleIdForDate(it) }
        assertThat(writtenIds).containsExactly(*expectedIds.toTypedArray())
    }

    @Test
    fun `loop stops on first failed day and marks subsequent days as skipped`() {
        // Stop-on-failure preserves cooldown causality: a retry of day N must not observe day N+1's clues.
        val repo = TrackingPuzzleRepository()
        val day3 = today.plusDays(2)
        val day3Seed = day3.toEpochDay() * 1_000_000_000L
        val port =
            RecordingPort(
                grids = { call -> if (call.seed in day3Seed until day3Seed + 1000L) null else successfulGrid() },
            )
        val useCase = newUseCase(repo, port)

        val summary = useCase.execute(today)

        assertThat(summary.failedDates).containsExactly(day3)
        assertThat(summary.skippedDates).containsExactly(
            today.plusDays(3),
            today.plusDays(4),
            today.plusDays(5),
            today.plusDays(6),
        )
        assertThat(summary.generatedDates).containsExactly(today, today.plusDays(1))
        // 2 successes (today, today+1) + 20 exhausted attempts on day 3; day 4..7 never call the port.
        assertThat(port.calls).hasSize(22)
        assertThat(repo.getOrComputeCalls).hasSize(2)
    }

    @Test
    fun `inner attempts and per attempt timeout are propagated to the port`() {
        val repo = TrackingPuzzleRepository()
        val port = RecordingPort(grids = { _ -> successfulGrid() })
        val useCase =
            EnsureUpcomingDailiesUseCase(
                puzzleRepository = repo,
                gridGenerationPort = port,
                dailyPuzzleSelector = selector,
                windowDays = 3,
                innerAttempts = 42,
                perAttemptTimeoutMs = 7_777L,
            )

        useCase.execute(today)

        assertThat(port.calls).hasSize(3)
        port.calls.forEach { call ->
            assertThat(call.attempts).isEqualTo(42)
            assertThat(call.perAttemptTimeoutMs).isEqualTo(7_777L)
        }
    }

    @Test
    fun `seed iteration stops once an attempt converges`() {
        val repo = TrackingPuzzleRepository()
        val targetDate = today
        val targetEpochDay = targetDate.toEpochDay()
        val port =
            RecordingPort(
                grids = { call -> if (call.seed < targetEpochDay * 1_000_000_000L + 250L) null else successfulGrid() },
            )
        val useCase = newUseCase(repo, port, windowDays = 1)

        useCase.execute(targetDate)

        val seenSeeds = port.calls.map { it.seed }
        val innerStride = EnsureUpcomingDailiesUseCase.DEFAULT_INNER_ATTEMPTS.toLong()
        val expectedSeeds = (0..5).map { targetEpochDay * 1_000_000_000L + it * innerStride }
        assertThat(seenSeeds).containsExactly(*expectedSeeds.toTypedArray())
    }

    @Test
    fun `seedFor produces non-overlapping inner-seed blocks across consecutive outer attempts`() {
        val useCase = newUseCase(TrackingPuzzleRepository(), RecordingPort(grids = { _ -> null }))
        val innerAttempts = EnsureUpcomingDailiesUseCase.DEFAULT_INNER_ATTEMPTS

        val outer0Last = useCase.seedFor(today, 0) + (innerAttempts - 1)
        val outer1First = useCase.seedFor(today, 1)
        assertThat(outer0Last).isLessThan(outer1First)
    }

    @Test
    fun `seedFor avoids cross-date collision even at the last outer attempt`() {
        val useCase = newUseCase(TrackingPuzzleRepository(), RecordingPort(grids = { _ -> null }))
        val maxAttempts = EnsureUpcomingDailiesUseCase.DEFAULT_MAX_ATTEMPTS
        val innerAttempts = EnsureUpcomingDailiesUseCase.DEFAULT_INNER_ATTEMPTS

        val lastOuterLastInner = useCase.seedFor(today, maxAttempts - 1) + (innerAttempts - 1)
        val nextDayFirst = useCase.seedFor(today.plusDays(1), 0)
        assertThat(lastOuterLastInner).isLessThan(nextDayFirst)
    }

    @Test
    fun `first day failure stops loop and remaining days are skipped not failed`() {
        val repo = TrackingPuzzleRepository()
        val port = RecordingPort(grids = { _ -> null })
        val useCase = newUseCase(repo, port, windowDays = 2)

        val summary = useCase.execute(today)

        assertThat(summary.failedDates).containsExactly(today)
        assertThat(summary.skippedDates).containsExactly(today.plusDays(1))
        assertThat(repo.getOrComputeCalls).isEmpty()
    }

    private fun newUseCase(
        repo: PuzzleRepository,
        port: GridGenerationPort,
        windowDays: Int = 7,
    ): EnsureUpcomingDailiesUseCase =
        EnsureUpcomingDailiesUseCase(
            puzzleRepository = repo,
            gridGenerationPort = port,
            dailyPuzzleSelector = selector,
            windowDays = windowDays,
        )

    private fun successfulGrid(): Grid {
        val word = Word(text = "ABCDE", definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        return Grid.fromPlacements(width = 5, height = 5, placements = listOf(placement))
    }

    private fun newStoredPuzzle(): StoredPuzzle =
        StoredPuzzle(
            grid = successfulGrid(),
            title = "Grille du jour",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-05-13T00:00:00Z"),
        )

    private class TrackingPuzzleRepository : PuzzleRepository {
        private val store = ConcurrentHashMap<UUID, StoredPuzzle>()
        val getOrComputeCalls = mutableListOf<UUID>()

        fun put(
            id: UUID,
            value: StoredPuzzle,
        ) {
            store[id] = value
        }

        override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

        override fun getOrCompute(
            puzzleId: UUID,
            factory: () -> StoredPuzzle?,
        ): StoredPuzzle? {
            val produced = factory() ?: return null
            getOrComputeCalls += puzzleId
            return store.computeIfAbsent(puzzleId) { produced }
        }
    }

    private data class PortCall(
        val seed: Long,
        val cooldownPolicy: ClueCooldownPolicy,
        val attempts: Int,
        val perAttemptTimeoutMs: Long,
    )

    private class RecordingPort(
        val grids: (PortCall) -> Grid?,
    ) : GridGenerationPort {
        val calls = mutableListOf<PortCall>()

        override fun generate(
            randomSeed: Long,
            cooldownPolicy: ClueCooldownPolicy,
            attempts: Int,
            perAttemptTimeoutMs: Long,
        ): Grid? {
            val call = PortCall(randomSeed, cooldownPolicy, attempts, perAttemptTimeoutMs)
            calls += call
            return grids(call)
        }
    }
}
