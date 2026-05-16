package com.bliss.grid.application.puzzle

import java.time.LocalDate
import java.util.UUID

/**
 * Returns a thin, newest-first list of daily puzzles for the `/grilles`
 * archive route. Date-to-id mapping is the deterministic
 * [DailyPuzzleSelector]; the repository projects to
 * (id, totalLetterCells). Per-user progress is computed client-side from
 * the solo-entries store and is intentionally absent from this contract.
 *
 * `hasMore` is true when the clamped range contains more rows than
 * [maxItems]; clients paginate by re-issuing the request with `to` set
 * to one day before the oldest returned `date`.
 */
class ListDailyPuzzlesUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val dailyPuzzleSelector: DailyPuzzleSelector = DailyPuzzleSelector(),
    private val launchAnchor: LocalDate = DEFAULT_LAUNCH_ANCHOR,
    private val defaultRangeDays: Int = DEFAULT_RANGE_DAYS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val defaultDifficulty: String = DEFAULT_DIFFICULTY,
) {
    fun execute(
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
    ): Result {
        val clampedTo = (to ?: today).coerceAtMost(today)
        val clampedFrom =
            (from ?: clampedTo.minusDays(defaultRangeDays.toLong()))
                .coerceAtLeast(launchAnchor)
        if (clampedFrom.isAfter(clampedTo)) return Result(items = emptyList(), hasMore = false)

        // Build the date list newest-first.
        val dates =
            buildList<LocalDate> {
                var d = clampedTo
                while (!d.isBefore(clampedFrom)) {
                    add(d)
                    d = d.minusDays(1)
                }
            }
        val ids = dates.map { dailyPuzzleSelector.puzzleIdForDate(it) }
        val summariesById: Map<UUID, StoredSummary> =
            puzzleRepository.findSummariesByIds(ids).associateBy { it.puzzleId }

        val mapped =
            dates.mapNotNull { date ->
                val id = dailyPuzzleSelector.puzzleIdForDate(date)
                val summary = summariesById[id] ?: return@mapNotNull null
                Item(
                    id = id,
                    date = date,
                    gridNumber = dailyPuzzleSelector.gridNumberForDate(date),
                    difficulty = defaultDifficulty,
                    totalLetterCells = summary.totalLetterCells,
                )
            }

        val hasMore = mapped.size > maxItems
        val items = if (hasMore) mapped.take(maxItems) else mapped
        return Result(items = items, hasMore = hasMore)
    }

    data class Result(val items: List<Item>, val hasMore: Boolean)

    data class Item(
        val id: UUID,
        val date: LocalDate,
        val gridNumber: Int,
        val difficulty: String?,
        val totalLetterCells: Int,
    )

    companion object {
        val DEFAULT_LAUNCH_ANCHOR: LocalDate = LocalDate.parse("2026-01-01")
        const val DEFAULT_RANGE_DAYS: Int = 31
        const val DEFAULT_MAX_ITEMS: Int = 100
        const val DEFAULT_DIFFICULTY: String = "facile"
    }
}
