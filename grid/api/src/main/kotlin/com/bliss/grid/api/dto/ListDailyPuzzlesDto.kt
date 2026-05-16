package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ListDailyPuzzlesResponseDto(
    val items: List<PuzzleSummaryDto>,
    val hasMore: Boolean,
)

@Serializable
data class PuzzleSummaryDto(
    val id: String,
    val date: String,
    val gridNumber: Int,
    val difficulty: DifficultyDto? = null,
    val totalLetterCells: Int,
)
