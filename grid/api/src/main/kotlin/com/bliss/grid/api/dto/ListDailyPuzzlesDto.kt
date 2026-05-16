package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

/**
 * Envelope returned by `listDailyPuzzles` (ADR-0003 §6 collection envelope).
 * `items` is newest-first and capped server-side; `hasMore` lets clients
 * page further back by re-querying with `to` set to one day before the
 * oldest returned `date`.
 */
@Serializable
data class ListDailyPuzzlesResponseDto(
    val items: List<PuzzleSummaryDto>,
    val hasMore: Boolean,
)

/**
 * Thin daily-puzzle summary returned by `listDailyPuzzles`. Mirrors the
 * `PuzzleSummary` schema in `grid/api/openapi.yaml`: `id` is the
 * deterministic UUID v7 derived from `date`, `gridNumber` is the day count
 * since the launch anchor, `difficulty` is `null` when no tier is
 * assigned, and `totalLetterCells` is the denominator for client-side
 * progress bars.
 */
@Serializable
data class PuzzleSummaryDto(
    val id: String,
    val date: String,
    val gridNumber: Int,
    val difficulty: DifficultyDto? = null,
    val totalLetterCells: Int,
)
