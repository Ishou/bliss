package com.bliss.game.domain

import java.time.Instant
import java.util.UUID

/**
 * Game-context projection of a puzzle. Mirrors the AsyncAPI `GamePuzzle`
 * shape; a parallel type lives in grid/domain. Cross-context imports are
 * forbidden (ADR-0001 §1) so each context owns its view.
 */
data class GamePuzzle(
    val id: UUID,
    val width: Int,
    val height: Int,
    val cells: List<GameCell>,
    val clues: List<GameClue>,
    val createdAt: Instant,
) {
    init {
        require(width in MIN_DIMENSION..MAX_DIMENSION) {
            "GamePuzzle width must be in $MIN_DIMENSION..$MAX_DIMENSION, was $width"
        }
        require(height in MIN_DIMENSION..MAX_DIMENSION) {
            "GamePuzzle height must be in $MIN_DIMENSION..$MAX_DIMENSION, was $height"
        }
        val seen = mutableSetOf<Position>()
        for (cell in cells) {
            require(cell.position.row in 0 until height && cell.position.column in 0 until width) {
                "Cell ${cell.position} out of bounds for ${width}x$height puzzle"
            }
            require(seen.add(cell.position)) { "Duplicate cell at ${cell.position}" }
        }
    }

    companion object {
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 50
    }
}
