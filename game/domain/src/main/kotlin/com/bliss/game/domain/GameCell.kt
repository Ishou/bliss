// game/'s view of the cell hierarchy. NOT a verbatim copy of grid/domain/model/Cell.kt:
// DefinitionCell carries a `clues: List<GameDefinitionClue>` of 1 or 2 entries,
// mirroring the `clues` array on GameDefinitionCell in game/api/asyncapi.yaml.
// The 2-clue case is the mots-fléchés corner-cell idiom (an across clue and a
// down clue stacked at the same position). The grid context's wire (see
// grid/api/openapi.yaml) emits one DefinitionCell per clue at the same position;
// the game infrastructure mapper groups them, so the domain sees a single cell
// with both clues. Resolves the deferral noted on PR #132's review of
// PuzzleResponseMapper.
// Cross-context imports are forbidden per ADR-0001 §1; the two hierarchies
// remain intentionally diverged but now agree on plural-clue cells.
package com.bliss.game.domain

import java.util.UUID

sealed interface GameCell {
    val position: Position
}

data class LetterCell(
    override val position: Position,
    val answer: Letter?,
) : GameCell

// 1 or 2 clues per cell — matches the `clues` array on GameDefinitionCell in
// game/api/asyncapi.yaml. The 2-clue case is the mots-fléchés corner-cell idiom
// (now supported across spec, domain, and mapper).
data class DefinitionCell(
    override val position: Position,
    val clues: List<GameDefinitionClue>,
) : GameCell {
    init {
        require(clues.isNotEmpty() && clues.size <= 2) {
            "DefinitionCell must carry 1 or 2 clues, got ${clues.size}"
        }
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
