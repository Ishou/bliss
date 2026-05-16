package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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

class ListDailyPuzzlesUseCaseTest {
    private val today = LocalDate.parse("2026-05-16")
    private val selector = DailyPuzzleSelector()
    private val repo = FakePuzzleRepository()

    private fun useCase(maxItems: Int = 100): ListDailyPuzzlesUseCase =
        ListDailyPuzzlesUseCase(
            puzzleRepository = repo,
            dailyPuzzleSelector = selector,
            launchAnchor = LocalDate.parse("2026-01-01"),
            defaultRangeDays = 31,
            maxItems = maxItems,
        )

    @Test
    fun `default range covers 31 days ending today`() {
        seedRows(LocalDate.parse("2026-04-15")..today)

        val result = useCase().execute(from = null, to = null, today = today)

        // 31 days back from 2026-05-16 inclusive of today = 2026-04-15..2026-05-16, 32 entries.
        assertThat(result.items.first().date).isEqualTo(today)
        assertThat(result.items.last().date).isEqualTo(LocalDate.parse("2026-04-15"))
        assertThat(result.items).hasSize(32)
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `items are sorted DESC by date`() {
        seedRows(LocalDate.parse("2026-05-10")..today)

        val result = useCase().execute(from = null, to = null, today = today)

        val dates = result.items.map { it.date }
        assertThat(dates).isEqualTo(dates.sortedDescending())
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `from earlier than launch anchor is clamped silently`() {
        seedRows(LocalDate.parse("2026-01-01")..today)

        // maxItems wide enough to surface the clamped lower bound.
        val result =
            useCase(maxItems = 1_000).execute(
                from = LocalDate.parse("2025-01-01"),
                to = today,
                today = today,
            )

        assertThat(result.items.last().date).isEqualTo(LocalDate.parse("2026-01-01"))
    }

    @Test
    fun `to later than today is clamped to today`() {
        seedRows(LocalDate.parse("2026-05-14")..today)

        val result =
            useCase().execute(
                from = LocalDate.parse("2026-05-14"),
                to = LocalDate.parse("2030-01-01"),
                today = today,
            )

        assertThat(result.items.first().date).isEqualTo(today)
    }

    @Test
    fun `from greater than to returns empty and hasMore false`() {
        seedRows(LocalDate.parse("2026-05-01")..today)

        val result =
            useCase().execute(
                from = LocalDate.parse("2026-05-10"),
                to = LocalDate.parse("2026-05-01"),
                today = today,
            )

        assertThat(result.items).isEmpty()
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `missing rows in the range are omitted from the result`() {
        // Seed only even-day dailies between 2026-05-02 and today.
        var d = LocalDate.parse("2026-05-01")
        while (!d.isAfter(today)) {
            if (d.dayOfMonth % 2 == 0) seedRows(d..d)
            d = d.plusDays(1)
        }

        val result =
            useCase().execute(
                from = LocalDate.parse("2026-05-01"),
                to = today,
                today = today,
            )

        val days = result.items.map { it.date.dayOfMonth }
        assertThat(days.all { it % 2 == 0 }).isTrue()
    }

    @Test
    fun `result is capped at maxItems and hasMore is true when truncated`() {
        seedRows(LocalDate.parse("2026-01-01")..today) // > 100 days

        val result =
            useCase(maxItems = 5).execute(
                from = LocalDate.parse("2026-01-01"),
                to = today,
                today = today,
            )

        assertThat(result.items).hasSize(5)
        assertThat(result.items.first().date).isEqualTo(today)
        assertThat(result.hasMore).isTrue()
    }

    @Test
    fun `hasMore is false when fetched size equals maxItems exactly with no surplus`() {
        // 2026-05-12..2026-05-16 inclusive = 5 days.
        seedRows(LocalDate.parse("2026-05-12")..today)

        val result =
            useCase(maxItems = 5).execute(
                from = LocalDate.parse("2026-05-12"),
                to = today,
                today = today,
            )

        assertThat(result.items).hasSize(5)
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `gridNumber is computed from launch anchor and difficulty is null when untiered`() {
        seedRows(LocalDate.parse("2026-01-01")..LocalDate.parse("2026-01-03"))

        val result =
            useCase().execute(
                from = LocalDate.parse("2026-01-01"),
                to = LocalDate.parse("2026-01-03"),
                today = today,
            )

        // Newest first: day 3, 2, 1.
        assertThat(result.items.map { it.gridNumber }).containsExactly(3, 2, 1)
        val difficulties = result.items.map { it.difficulty }
        assertThat(difficulties.all { it == null }).isTrue()
    }

    private fun seedRows(range: ClosedRange<LocalDate>) {
        var d = range.start
        while (!d.isAfter(range.endInclusive)) {
            val id = selector.puzzleIdForDate(d)
            repo.getOrCompute(id) { sampleStoredPuzzle() }
            d = d.plusDays(1)
        }
    }

    private class FakePuzzleRepository : PuzzleRepository {
        private val store = ConcurrentHashMap<UUID, StoredPuzzle>()

        override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

        override fun getOrCompute(
            puzzleId: UUID,
            factory: () -> StoredPuzzle?,
        ): StoredPuzzle? {
            store[puzzleId]?.let { return it }
            val produced = factory() ?: return null
            return store.putIfAbsent(puzzleId, produced) ?: produced
        }
        // findSummariesByIds inherits the interface default (per-id get + derive).
    }

    private fun sampleStoredPuzzle(): StoredPuzzle {
        val word = Word(text = "ABCDE", definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        return StoredPuzzle(
            grid = Grid.fromPlacements(width = 5, height = 5, placements = listOf(placement)),
            title = "t",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-05-13T00:00:00Z"),
        )
    }
}
