// Mirrors grid/domain/model/Cell.kt's Cell hierarchy verbatim. Per ADR-0001 §1
// cross-context imports are forbidden; the game/ context owns its view of the
// puzzle shape. Update both files together when the puzzle shape changes.
package com.bliss.game.domain

import java.util.UUID

sealed interface GameCell {
    val position: Position
}

data class LetterCell(
    override val position: Position,
    val answer: Letter?,
) : GameCell

// TODO(schema): GameDefinitionCell in game/api/asyncapi.yaml currently models a single clue
// (flat clueId/text/arrow). This domain type supports the full dual-arrow mots-fléchés shape.
// A schema amendment PR must be merged before the :game:api implementation PR opens (ADR-0001 §3).
data class DefinitionCell(
    override val position: Position,
    val clues: List<GameDefinitionClue>,
) : GameCell {
    init {
        require(clues.isNotEmpty()) { "DefinitionCell must have at least one clue" }
        require(clues.size <= MAX_CLUES) { "DefinitionCell may hold at most $MAX_CLUES clues, was ${clues.size}" }
        require(clues.map { it.arrow }.toSet().size == clues.size) {
            "DefinitionCell may not contain two clues with the same arrow"
        }
    }

    companion object {
        const val MAX_CLUES = 2
    }
}

data class BlockCell(
    override val position: Position,
) : GameCell

enum class GameArrow {
    RIGHT,
    DOWN,
    DOWN_RIGHT,
    RIGHT_DOWN,
}

enum class GameClueDirection {
    ACROSS,
    DOWN,
}

data class GameDefinitionClue(
    val id: UUID,
    val text: String,
    val arrow: GameArrow,
) {
    init {
        require(text.isNotBlank()) { "GameDefinitionClue text must not be blank" }
        require(text.length <= MAX_TEXT_LENGTH) {
            "GameDefinitionClue text must be at most $MAX_TEXT_LENGTH chars, was ${text.length}"
        }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 500
    }
}

data class GameClue(
    val id: UUID,
    val direction: GameClueDirection,
    val start: Position,
    val length: Int,
    val text: String,
) {
    init {
        require(length in 1..MAX_LENGTH) { "GameClue length must be in 1..$MAX_LENGTH, was $length" }
        require(text.isNotBlank()) { "GameClue text must not be blank" }
        require(text.length <= MAX_TEXT_LENGTH) {
            "GameClue text must be at most $MAX_TEXT_LENGTH chars, was ${text.length}"
        }
    }

    companion object {
        const val MAX_LENGTH = 50
        const val MAX_TEXT_LENGTH = 500
    }
}
