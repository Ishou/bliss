package com.bliss.game.domain

import java.time.Instant
import java.util.UUID

/**
 * Game-context projection of a puzzle. This is a game-logic view — fields present
 * here are those the game engine needs; api-layer DTOs carry additional display fields.
 * A parallel type lives in grid/domain; cross-context imports are forbidden (ADR-0001 §1).
 */
data class GamePuzzle(
    val id: UUID,
    val title: String,
    val language: String,
    val width: Int,
    val height: Int,
    val cells: List<GameCell>,
    val clues: List<GameClue>,
    val createdAt: Instant,
) {
    init {
        require(title.isNotBlank()) { "GamePuzzle title must not be blank" }
        require(title.length <= MAX_TITLE_LENGTH) {
            "GamePuzzle title must be at most $MAX_TITLE_LENGTH chars, was ${title.length}"
        }
        require(LANGUAGE_REGEX.matches(language)) {
            "GamePuzzle language must be a BCP-47 tag, was '$language'"
        }
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
        const val MAX_TITLE_LENGTH = 200
        val LANGUAGE_REGEX = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}
