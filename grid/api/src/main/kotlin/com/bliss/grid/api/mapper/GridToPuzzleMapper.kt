package com.bliss.grid.api.mapper

import com.bliss.grid.api.dto.BlockCellDto
import com.bliss.grid.api.dto.CellDto
import com.bliss.grid.api.dto.ClueDto
import com.bliss.grid.api.dto.DefinitionCellDto
import com.bliss.grid.api.dto.LetterCellDto
import com.bliss.grid.api.dto.PuzzleResponse
import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.EmptyCell
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.WordPlacement
import com.fasterxml.uuid.Generators
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Maps a domain [Grid] to the OpenAPI [PuzzleResponse].
 *
 * Mots-fléchés stores the clue text inside a `definition` cell adjacent to
 * the answer. The wire shape keeps `clues` separate (ADR-0003: clients tally
 * completion without walking the cell array) and cross-links via `clueId`.
 *
 * The OpenAPI spec is authoritative: cells emit row-major, every clueId on
 * a `DefinitionCellDto` resolves in `clues[]`, every coordinate is
 * zero-indexed.
 */
class GridToPuzzleMapper {
    private val uuidGenerator = Generators.timeBasedEpochGenerator()

    fun toApi(
        grid: Grid,
        puzzleId: UUID,
        createdAt: Instant,
        title: String = "Grille du jour",
        language: String = "fr",
    ): PuzzleResponse {
        val clueIdByPlacement: Map<WordPlacement, String> =
            grid.placements.associateWith { uuidGenerator.generate().toString() }

        val clues =
            grid.placements.map { placement ->
                ClueDto(
                    id = clueIdByPlacement.getValue(placement),
                    direction = placement.direction.toApiClueDirection(),
                    start = placement.startPosition().toDto(),
                    length = placement.word.text.length,
                    text = placement.word.definition,
                )
            }

        val cells = buildCells(grid, clueIdByPlacement)

        return PuzzleResponse(
            id = puzzleId.toString(),
            title = title,
            language = language,
            width = grid.width,
            height = grid.height,
            cells = cells,
            clues = clues,
            createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt),
        )
    }

    private fun buildCells(
        grid: Grid,
        clueIdByPlacement: Map<WordPlacement, String>,
    ): List<CellDto> {
        val cells = mutableListOf<CellDto>()
        for (r in 0 until grid.height) {
            for (c in 0 until grid.width) {
                val pos = Position(Row(r), Column(c))
                val dto = pos.toDto()
                when (val cell = grid.cells[pos]) {
                    null, EmptyCell -> cells += BlockCellDto(position = dto)
                    is LetterCell -> cells += LetterCellDto(position = dto, letter = cell.letter.toString())
                    is ClueCell -> {
                        cell.clues.forEach { clue ->
                            val placement =
                                grid.placements.firstOrNull { it.cluePosition == pos && it.direction == clue.direction }
                                    ?: error("no WordPlacement for $pos / ${clue.direction} — Grid invariant violated")
                            cells +=
                                DefinitionCellDto(
                                    position = dto,
                                    clueId = clueIdByPlacement.getValue(placement),
                                    text = clue.definition,
                                    arrow = clue.direction.toApiArrow(),
                                )
                        }
                    }
                }
            }
        }
        return cells
    }
}

private fun Position.toDto(): CellDto.PositionDto = CellDto.PositionDto(row = row.value, column = column.value)

private fun WordPlacement.startPosition(): Position {
    val dr = direction.startOffset.row.value
    val dc = direction.startOffset.column.value
    return Position(
        Row(cluePosition.row.value + dr),
        Column(cluePosition.column.value + dc),
    )
}

private fun Direction.toApiArrow(): String =
    when (this) {
        Direction.RIGHT -> "right"
        Direction.DOWN -> "down"
    }

private fun Direction.toApiClueDirection(): String =
    when (this) {
        Direction.RIGHT -> "across"
        Direction.DOWN -> "down"
    }
