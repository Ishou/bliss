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

// Single clue per cell — matches the flat GameDefinitionCell shape in game/api/asyncapi.yaml.
// Dual-clue mots-fléchés semantics are deferred to a future schema-amendment + domain PR pair
// (ADR-0001 §3).
data class DefinitionCell(
    override val position: Position,
    val clue: GameDefinitionClue,
) : GameCell

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
