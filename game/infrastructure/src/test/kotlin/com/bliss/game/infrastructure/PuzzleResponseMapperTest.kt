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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

// Tests focus on translation edge cases not exercised by HttpPuzzleProviderTest's happy path:
// - every arrow variant; unknown enum values
// - clue direction mapping
// - stacked-DefinitionCell preservation (both clues kept, in wire order; see mapper header)
// - nullable LetterCell.letter
class PuzzleResponseMapperTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"
    private val clueId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d"
    private val clueIdB = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e"

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
            assertThat(mapped.clues.single().arrow).isEqualTo(expected)
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
                        ClueDto(clueIdB, "down", PositionDto(0, 1), 6, "psycho"),
                    ),
            )
        assertThat(dto.toDomain().clues.map { it.direction })
            .containsExactly(GameClueDirection.ACROSS, GameClueDirection.DOWN)
    }

    @Test
    fun `stacked definition cells at the same position preserve both clues in wire order`() {
        val dto =
            base(
                listOf(
                    CellDto.Definition(PositionDto(0, 0), clueId, "first", "down"),
                    CellDto.Definition(PositionDto(0, 0), clueIdB, "second", "right"),
                ),
            )
        val cells = dto.toDomain().cells
        assertThat(cells).hasSize(1)
        val def = cells.single() as DefinitionCell
        assertThat(def.clues.map { it.text }).containsExactly("first", "second")
        assertThat(def.clues.map { it.arrow }).containsExactly(GameArrow.DOWN, GameArrow.RIGHT)
    }

    @Test
    fun `random 5x5 fixture with N stacked corners round-trips with clue count and order preserved`() {
        // Property-style test: for each N in 0..4, build a wire with N stacked corner cells
        // (each corner getting an across+down pair) and assert the mapper's domain output
        // has one DefinitionCell per corner with exactly two clues in wire order.
        repeat(20) {
            val stackedCorners = Arb.int(0..4).next()
            val corners = listOf(0 to 0, 0 to 4, 4 to 0, 4 to 4).take(stackedCorners)
            val cellDtos =
                corners.flatMap { (r, c) ->
                    val acrossId = UUID.randomUUID().toString()
                    val downId = UUID.randomUUID().toString()
                    listOf(
                        CellDto.Definition(PositionDto(r, c), acrossId, "across-$r-$c", "right"),
                        CellDto.Definition(PositionDto(r, c), downId, "down-$r-$c", "down"),
                    )
                }
            val mapped = base(cellDtos).toDomain().cells.filterIsInstance<DefinitionCell>()
            assertThat(mapped).hasSize(stackedCorners)
            for ((index, def) in mapped.withIndex()) {
                val (r, c) = corners[index]
                assertThat(def.clues.map { it.text }).containsExactly("across-$r-$c", "down-$r-$c")
                assertThat(def.clues.map { it.arrow }).containsExactly(GameArrow.RIGHT, GameArrow.DOWN)
            }
        }
    }

    @Test
    fun `unknown arrow value throws IllegalArgumentException`() {
        val dto = base(listOf(CellDto.Definition(PositionDto(0, 0), clueId, "x", "diagonal")))
        assertThrows<IllegalArgumentException> { dto.toDomain() }
    }
}
