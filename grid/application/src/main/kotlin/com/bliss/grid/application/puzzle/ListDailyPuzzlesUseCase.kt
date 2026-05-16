package com.bliss.grid.application.puzzle

import java.time.LocalDate
import java.util.UUID

class ListDailyPuzzlesUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val dailyPuzzleSelector: DailyPuzzleSelector = DailyPuzzleSelector(),
    private val launchAnchor: LocalDate = DEFAULT_LAUNCH_ANCHOR,
    private val defaultRangeDays: Int = DEFAULT_RANGE_DAYS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
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
            dates.zip(ids).mapNotNull { (date, id) ->
                val summary = summariesById[id] ?: return@mapNotNull null
                Item(
                    id = id,
                    date = date,
                    gridNumber = dailyPuzzleSelector.gridNumberForDate(date),
                    difficulty = null,
                    totalLetterCells = summary.totalLetterCells,
                )
            }

        val hasMore = mapped.size > maxItems
        val items = if (hasMore) mapped.take(maxItems) else mapped
        return Result(items = items, hasMore = hasMore)
    }

    data class Result(
        val items: List<Item>,
        val hasMore: Boolean,
    )

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
    }
}
