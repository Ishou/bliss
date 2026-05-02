// Wire-to-domain translation for the grid puzzle response. Pure: no IO, no clock.
// The DTO never escapes infrastructure; only GamePuzzle / GameCell / GameClue do.
//
// Stacked DefinitionCells: grid/api/openapi.yaml allows two `definition` cells at the
// same position (the mots-fléchés "two clues per cell" idiom). game:domain's
// DefinitionCell carries a single clue (see comment at top of GameCell.kt — dual-clue
// support is deferred per ADR-0001 §3, awaiting a schema amendment). Until that lands,
// the mapper keeps the FIRST definition cell encountered at each position and drops
// subsequent stacks. This is lossy on grids that exercise the dual-clue idiom but it
// keeps GamePuzzle's "no duplicate position" invariant satisfied. Stacked grids will
// be visibly missing a clue rather than crashing the lobby; the deferred PR fixes
// it properly.
package com.bliss.game.infrastructure

import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.GameCell
import com.bliss.game.domain.GameClue
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GameDefinitionClue
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Position
import java.time.Instant
import java.util.UUID

internal fun PuzzleResponseDto.toDomain(): GamePuzzle {
    val seenPositions = HashSet<Position>(cells.size)
    val mappedCells = ArrayList<GameCell>(cells.size)
    for (cellDto in cells) {
        val position = cellDto.position.toDomain()
        // Drop stacked DefinitionCells — see file header. Block / Letter cells never
        // legally stack on the wire, so a duplicate of those is actual upstream junk;
        // we still drop rather than throw to keep the lobby resilient to schema drift.
        if (!seenPositions.add(position)) continue
        mappedCells += cellDto.toDomain(position)
    }
    return GamePuzzle(
        id = UUID.fromString(id),
        title = title,
        language = language,
        width = width,
        height = height,
        cells = mappedCells,
        clues = clues.map { it.toDomain() },
        createdAt = Instant.parse(createdAt),
    )
}

private fun PositionDto.toDomain(): Position = Position(row = row, column = column)

private fun CellDto.toDomain(position: Position): GameCell =
    when (this) {
        is CellDto.Letter ->
            LetterCell(
                position = position,
                answer = letter?.firstOrNull()?.let { Letter(it) },
            )
        is CellDto.Definition ->
            DefinitionCell(
                position = position,
                clue =
                    GameDefinitionClue(
                        id = UUID.fromString(clueId),
                        text = text,
                        arrow = arrow.toGameArrow(),
                    ),
            )
        is CellDto.Block -> BlockCell(position = position)
    }

private fun ClueDto.toDomain(): GameClue =
    GameClue(
        id = UUID.fromString(id),
        direction = direction.toGameClueDirection(),
        start = start.toDomain(),
        length = length,
        text = text,
    )

// Wire enums are kebab-case (e.g. "down-right"); domain enums are SHOUT_SNAKE.
private fun String.toGameArrow(): GameArrow =
    when (this) {
        "right" -> GameArrow.RIGHT
        "down" -> GameArrow.DOWN
        "down-right" -> GameArrow.DOWN_RIGHT
        "right-down" -> GameArrow.RIGHT_DOWN
        else -> throw IllegalArgumentException("Unknown arrow direction: '$this'")
    }

private fun String.toGameClueDirection(): GameClueDirection =
    when (this) {
        "across" -> GameClueDirection.ACROSS
        "down" -> GameClueDirection.DOWN
        else -> throw IllegalArgumentException("Unknown clue direction: '$this'")
    }
