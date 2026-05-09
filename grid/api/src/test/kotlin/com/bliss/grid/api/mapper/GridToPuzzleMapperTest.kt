package com.bliss.grid.api.mapper

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.api.dto.BlockCellDto
import com.bliss.grid.api.dto.DefinitionCellDto
import com.bliss.grid.api.dto.LetterCellDto
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Unit tests for [GridToPuzzleMapper] — domain `Grid` → API `PuzzleResponse`. */
class GridToPuzzleMapperTest {
    private val mapper = GridToPuzzleMapper()

    private fun pos(
        r: Int,
        c: Int,
    ): Position = Position(Row(r), Column(c))

    private fun gridWith(
        width: Int = 5,
        height: Int = 5,
        placements: List<WordPlacement>,
    ): Grid = Grid.fromPlacements(width, height, placements)

    @Test
    fun `maps grid header fields and emits one cell per position in row-major order`() {
        val grid =
            gridWith(
                width = 6,
                height = 6,
                placements =
                    listOf(
                        WordPlacement(Word("PARIS", "Capitale"), pos(0, 0), Direction.RIGHT),
                    ),
            )
        val puzzleId = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
        val createdAt = Instant.parse("2026-04-24T15:30:00Z")

        val response = mapper.toApi(grid = grid, puzzleId = puzzleId, createdAt = createdAt, hintsAllowed = 3)

        assertThat(response.id).isEqualTo(puzzleId.toString())
        assertThat(response.width).isEqualTo(6)
        assertThat(response.height).isEqualTo(6)
        assertThat(response.language).isEqualTo("fr")
        assertThat(response.cells).hasSize(36)
        response.cells.forEachIndexed { i, cell ->
            assertThat(cell.position.row).isEqualTo(i / 6)
            assertThat(cell.position.column).isEqualTo(i % 6)
        }
    }

    @Test
    fun `maps Letter, Definition, and Block cells per OpenAPI discriminator`() {
        val grid =
            gridWith(
                width = 3,
                height = 3,
                placements =
                    listOf(
                        WordPlacement(Word("OR", "metal precieux"), pos(0, 0), Direction.RIGHT),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)

        // 1 ClueCell + 2 LetterCells + 6 EmptyCells = 9. Letters intentionally
        // not on the wire (schema PR #218); presence of two LetterCellDtos at
        // the OR positions is the contract.
        assertThat(response.cells.filterIsInstance<LetterCellDto>()).hasSize(2)
        val defs = response.cells.filterIsInstance<DefinitionCellDto>()
        assertThat(defs).hasSize(1)
        assertThat(defs.single().text).isEqualTo("metal precieux")
        assertThat(defs.single().arrow).isEqualTo("right")
        assertThat(defs.single().position.row).isEqualTo(0)
        assertThat(defs.single().position.column).isEqualTo(0)
        assertThat(response.cells.filterIsInstance<BlockCellDto>()).hasSize(6)
    }

    @Test
    fun `clueCell with two clues stacks two DefinitionCells with cross-linked clueIds`() {
        val grid =
            gridWith(
                placements =
                    listOf(
                        WordPlacement(Word("ROSE", "fleur"), pos(0, 0), Direction.RIGHT),
                        WordPlacement(Word("CHAT", "felin"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)

        val defsAtOrigin =
            response.cells
                .filterIsInstance<DefinitionCellDto>()
                .filter { it.position.row == 0 && it.position.column == 0 }
        assertThat(defsAtOrigin).hasSize(2)
        assertThat(defsAtOrigin.map { it.arrow }).containsExactlyInAnyOrder("right", "down")
        // Every DefinitionCellDto.clueId resolves in clues[].
        val clueIds = response.clues.map { it.id }.toSet()
        defsAtOrigin.forEach { def ->
            assertThat(clueIds.contains(def.clueId)).isTrue()
        }
    }

    @Test
    fun `difficulty and gridNumber default to null and pass through when supplied`() {
        val grid =
            gridWith(
                placements =
                    listOf(
                        WordPlacement(Word("OR", "metal"), pos(0, 0), Direction.RIGHT),
                    ),
            )

        val withDefaults = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)
        assertThat(withDefaults.difficulty).isNull()
        assertThat(withDefaults.gridNumber).isNull()

        val withValues =
            mapper.toApi(
                grid = grid,
                puzzleId = UUID.randomUUID(),
                createdAt = Instant.now(),
                hintsAllowed = 3,
                difficulty = "facile",
                gridNumber = 142,
            )
        assertThat(withValues.difficulty).isEqualTo("facile")
        assertThat(withValues.gridNumber).isEqualTo(142)
    }

    @Test
    fun `each WordPlacement produces one Clue with correct length, direction, and text`() {
        val grid =
            gridWith(
                placements =
                    listOf(
                        WordPlacement(Word("ROSE", "fleur"), pos(0, 0), Direction.RIGHT),
                        WordPlacement(Word("CHAT", "felin"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now(), hintsAllowed = 3)

        assertThat(response.clues).hasSize(2)
        response.clues.forEach { clue ->
            assertThat(clue.length).isEqualTo(4)
            assertThat(clue.direction).isIn("across", "down")
            assertThat(clue.text).isNotNull()
        }
    }
}
