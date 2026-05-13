package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
        assertThat(summary.persistedDates).isEmpty()
        // Each date sees exactly one generator invocation, in date order.
        val seenSeeds = port.calls.map { it.seed }
        val expectedSeeds = expectedDates.map { it.toEpochDay() * 1000L }
        assertThat(seenSeeds).containsExactly(*expectedSeeds.toTypedArray())
        // Repository writes happen in the same order as the dates.
        val writtenIds = repo.getOrComputeCalls.toList()
        val expectedIds = expectedDates.map { selector.puzzleIdForDate(it) }
        assertThat(writtenIds).containsExactly(*expectedIds.toTypedArray())
    }

    @Test
    fun `day three exhausts attempts and subsequent days still attempted`() {
        val repo = TrackingPuzzleRepository()
        val day3 = today.plusDays(2)
        val day3Seed = day3.toEpochDay() * 1000L
        val port =
            RecordingPort(
                grids = { call -> if (call.seed in day3Seed until day3Seed + 20) null else successfulGrid() },
            )
        val useCase = newUseCase(repo, port)

        val summary = useCase.execute(today)

        assertThat(summary.failedDates).containsExactly(day3)
        val expectedSurvivors =
            listOf(
                today,
                today.plusDays(1),
                today.plusDays(3),
                today.plusDays(4),
                today.plusDays(5),
                today.plusDays(6),
            )
        assertThat(summary.generatedDates).containsExactly(*expectedSurvivors.toTypedArray())
        // 20 attempts on day3 + 1 each on the six survivors = 26.
        assertThat(port.calls.count { it.seed in day3Seed until day3Seed + 20 }).isEqualTo(20)
        assertThat(port.calls.count { it.seed !in day3Seed until day3Seed + 20 }).isEqualTo(6)
        // Failed day was not persisted.
        assertThat(repo.getOrComputeCalls).hasSize(6)
    }

    @Test
    fun `seed iteration stops once an attempt converges`() {
        val repo = TrackingPuzzleRepository()
        val targetDate = today
        val targetEpochDay = targetDate.toEpochDay()
        // Fail attempts 0..4, succeed on attempt 5.
        val port =
            RecordingPort(
                grids = { call -> if (call.seed < targetEpochDay * 1000L + 5) null else successfulGrid() },
            )
        val useCase = newUseCase(repo, port, windowDays = 1)

        useCase.execute(targetDate)

        val seenSeeds = port.calls.map { it.seed }
        val expectedSeeds = (0..5).map { targetEpochDay * 1000L + it }
        assertThat(seenSeeds).containsExactly(*expectedSeeds.toTypedArray())
    }

    @Test
    fun `failed days are not persisted`() {
        val repo = TrackingPuzzleRepository()
        val port = RecordingPort(grids = { _ -> null })
        val useCase = newUseCase(repo, port, windowDays = 2)

        val summary = useCase.execute(today)

        assertThat(summary.failedDates).hasSize(2)
        assertThat(summary.failedDates).contains(today)
        assertThat(summary.failedDates).contains(today.plusDays(1))
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
    )

    private class RecordingPort(
        val grids: (PortCall) -> Grid?,
    ) : GridGenerationPort {
        val calls = mutableListOf<PortCall>()

        override fun generate(
            randomSeed: Long,
            cooldownPolicy: ClueCooldownPolicy,
        ): Grid? {
            val call = PortCall(randomSeed, cooldownPolicy)
            calls += call
            return grids(call)
        }
    }
}
