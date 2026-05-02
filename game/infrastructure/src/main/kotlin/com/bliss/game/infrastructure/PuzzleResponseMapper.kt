// Wire-to-domain translation for the grid puzzle response. Pure: no IO, no clock.
// The DTO never escapes infrastructure; only GamePuzzle / GameCell / GameClue do.
//
// Stacked DefinitionCells: grid/api/openapi.yaml emits one `definition` wire cell
// per clue at the same position (the mots-fléchés corner-cell idiom — an across
// clue and a down clue at one cell). game:domain's DefinitionCell now carries a
// `clues: List<GameDefinitionClue>` of 1 or 2 entries (see GameCell.kt's header
// comment). The mapper groups consecutive wire DefinitionCells sharing a position
// into a single domain DefinitionCell whose `clues` list preserves wire order.
// Block / Letter cells never legally stack; a duplicate position for those is
// actual upstream junk and we drop it rather than throw to keep the lobby
// resilient to schema drift.
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
    val mappedCells = ArrayList<GameCell>(cells.size)
    val definitionIndexByPosition = HashMap<Position, Int>()
    val seenNonDefinitionPositions = HashSet<Position>()
    for (cellDto in cells) {
        val position = cellDto.position.toDomain()
        when (cellDto) {
            is CellDto.Definition -> {
                val clue = cellDto.toDomainClue()
                val existing = definitionIndexByPosition[position]
                if (existing == null) {
                    definitionIndexByPosition[position] = mappedCells.size
                    mappedCells += DefinitionCell(position = position, clues = listOf(clue))
                } else {
                    val previous = mappedCells[existing] as DefinitionCell
                    if (previous.clues.size < 2) {
                        mappedCells[existing] = previous.copy(clues = previous.clues + clue)
                    } // else: 3rd+ clue at same position — drop, same resilience policy as Letter/Block duplicates
                }
            }
            is CellDto.Letter, is CellDto.Block -> {
                if (!seenNonDefinitionPositions.add(position)) continue
                mappedCells += cellDto.toDomain(position)
            }
        }
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

private fun CellDto.Definition.toDomainClue(): GameDefinitionClue =
    GameDefinitionClue(
        id = UUID.fromString(clueId),
        text = text,
        arrow = arrow.toGameArrow(),
    )

private fun CellDto.toDomain(position: Position): GameCell =
    when (this) {
        is CellDto.Letter ->
            LetterCell(
                position = position,
                answer = letter?.firstOrNull()?.let { Letter(it) },
            )
        is CellDto.Definition ->
            error("unreachable: Definition cells are handled inline in PuzzleResponseDto.toDomain()")
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
