package com.bliss.game.infrastructure

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.LetterCell
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// Tests focus on translation edge cases not exercised by HttpPuzzleProviderTest's happy path:
// - every arrow variant; unknown enum values
// - clue direction mapping
// - stacked-DefinitionCell dedup (see PuzzleResponseMapper file header)
// - nullable LetterCell.letter
class PuzzleResponseMapperTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"
    private val clueId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d"

    private fun base(
        cells: List<CellDto>,
        clues: List<ClueDto> = emptyList(),
    ): PuzzleResponseDto = PuzzleResponseDto(puzzleId, "T", "fr", 5, 5, cells, clues, "2026-04-30T06:46:14.109Z")

    @Test
    fun `letter cell with absent letter maps to null answer`() {
        val dto = base(listOf(CellDto.Letter(PositionDto(0, 1), letter = null)))
        val cell = dto.toDomain().cells.single() as LetterCell
        assertThat(cell.answer).isNull()
    }

    @Test
    fun `every wire arrow variant maps to its domain enum`() {
        val variants =
            listOf(
                "right" to GameArrow.RIGHT,
                "down" to GameArrow.DOWN,
                "down-right" to GameArrow.DOWN_RIGHT,
                "right-down" to GameArrow.RIGHT_DOWN,
            )
        for ((wire, expected) in variants) {
            val dto = base(listOf(CellDto.Definition(PositionDto(0, 0), clueId, "x", wire)))
            val mapped = dto.toDomain().cells.single() as DefinitionCell
            assertThat(mapped.clue.arrow).isEqualTo(expected)
        }
    }

    @Test
    fun `clue direction enum maps for both across and down`() {
        val dto =
            base(
                cells = emptyList(),
                clues =
                    listOf(
                        ClueDto(clueId, "across", PositionDto(1, 0), 5, "yogis"),
                        ClueDto(
                            "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e",
                            "down",
                            PositionDto(0, 1),
                            6,
                            "psycho",
                        ),
                    ),
            )
        assertThat(dto.toDomain().clues.map { it.direction })
            .containsExactly(GameClueDirection.ACROSS, GameClueDirection.DOWN)
    }

    @Test
    fun `stacked definition cells at the same position keep only the first`() {
        val dto =
            base(
                listOf(
                    CellDto.Definition(PositionDto(0, 0), clueId, "first", "down"),
                    CellDto.Definition(
                        PositionDto(0, 0),
                        "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e",
                        "second",
                        "right",
                    ),
                ),
            )
        val cells = dto.toDomain().cells
        assertThat(cells).hasSize(1)
        assertThat((cells.single() as DefinitionCell).clue.text).isEqualTo("first")
    }

    @Test
    fun `unknown arrow value throws IllegalArgumentException`() {
        val dto = base(listOf(CellDto.Definition(PositionDto(0, 0), clueId, "x", "diagonal")))
        assertThrows<IllegalArgumentException> { dto.toDomain() }
    }
}
