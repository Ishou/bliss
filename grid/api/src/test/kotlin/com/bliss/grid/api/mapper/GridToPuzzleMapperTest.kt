package com.bliss.grid.api.mapper

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.grid.api.dto.BlockCellDto
import com.bliss.grid.api.dto.CellDto
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

    @Test
    fun `maps a 6x6 grid to a row-major cell array of 36 cells`() {
        val grid =
            Grid.fromPlacements(
                width = 6,
                height = 6,
                placements =
                    listOf(
                        WordPlacement(
                            word = Word("PARIS", "Capitale de la France"),
                            cluePosition = pos(0, 0),
                            direction = Direction.RIGHT,
                        ),
                    ),
            )
        val puzzleId = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
        val createdAt = Instant.parse("2026-04-24T15:30:00Z")

        val response = mapper.toApi(grid = grid, puzzleId = puzzleId, createdAt = createdAt)

        assertThat(response.id).isEqualTo(puzzleId.toString())
        assertThat(response.width).isEqualTo(6)
        assertThat(response.height).isEqualTo(6)
        assertThat(response.language).isEqualTo("fr")
        assertThat(response.cells).hasSize(36)
        // Row-major: index 0 is (0,0), index 7 is (1,1), etc.
        response.cells.forEachIndexed { i, cell ->
            val expectedRow = i / 6
            val expectedCol = i % 6
            assertThat(cell.position.row).isEqualTo(expectedRow)
            assertThat(cell.position.column).isEqualTo(expectedCol)
        }
    }

    @Test
    fun `maps domain LetterCell to api LetterCellDto with uppercase letter`() {
        val grid =
            Grid.fromPlacements(
                width = 3,
                height = 3,
                placements =
                    listOf(
                        WordPlacement(
                            word = Word("OR", "metal precieux"),
                            cluePosition = pos(0, 0),
                            direction = Direction.RIGHT,
                        ),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        // Expected letter cells: (0,1)='O' and (0,2)='R'
        val letters: List<LetterCellDto> = response.cells.filterIsInstance<LetterCellDto>()
        assertThat(letters).hasSize(2)
        assertThat(letters.map { it.letter }).containsExactlyInAnyOrder("O", "R")
    }

    @Test
    fun `maps domain ClueCell with one clue to one DefinitionCellDto`() {
        val grid =
            Grid.fromPlacements(
                width = 3,
                height = 3,
                placements =
                    listOf(
                        WordPlacement(
                            word = Word("OR", "metal precieux"),
                            cluePosition = pos(0, 0),
                            direction = Direction.RIGHT,
                        ),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        val defs: List<DefinitionCellDto> = response.cells.filterIsInstance<DefinitionCellDto>()
        assertThat(defs).hasSize(1)
        val def = defs.single()
        assertThat(def.text).isEqualTo("metal precieux")
        assertThat(def.arrow).isEqualTo("right")
        assertThat(def.position.row).isEqualTo(0)
        assertThat(def.position.column).isEqualTo(0)
    }

    @Test
    fun `every clueId on a DefinitionCellDto resolves to a clue in clues array`() {
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(Word("ROSE", "fleur"), pos(0, 0), Direction.RIGHT),
                        WordPlacement(Word("CHAT", "felin"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        val clueIds = response.clues.map { it.id }.toSet()
        val referenced =
            response.cells
                .filterIsInstance<DefinitionCellDto>()
                .map { it.clueId }
        referenced.forEach { id ->
            assertThat(clueIds.contains(id)).isTrue()
        }
    }

    @Test
    fun `clueCell with two clues produces two DefinitionCellDtos at the same position`() {
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(Word("ROSE", "fleur"), pos(0, 0), Direction.RIGHT),
                        WordPlacement(Word("CHAT", "felin"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        val defsAtOrigin =
            response.cells
                .filterIsInstance<DefinitionCellDto>()
                .filter { it.position.row == 0 && it.position.column == 0 }
        assertThat(defsAtOrigin).hasSize(2)
        assertThat(defsAtOrigin.map { it.arrow }).containsExactlyInAnyOrder("right", "down")
    }

    @Test
    fun `empty domain cells map to BlockCellDto`() {
        val grid =
            Grid.fromPlacements(
                width = 3,
                height = 3,
                placements =
                    listOf(
                        WordPlacement(Word("OR", "x"), pos(0, 0), Direction.RIGHT),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        // 1 ClueCell + 2 LetterCells + 6 BlockCells = 9 cells
        val blocks: List<BlockCellDto> = response.cells.filterIsInstance<BlockCellDto>()
        assertThat(blocks).hasSize(6)
    }

    @Test
    fun `each WordPlacement produces one Clue with correct length and direction`() {
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(Word("ROSE", "fleur"), pos(0, 0), Direction.RIGHT),
                        WordPlacement(Word("CHAT", "felin"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        assertThat(response.clues).hasSize(2)
        response.clues.forEach { clue ->
            assertThat(clue.length).isEqualTo(4)
            assertThat(clue.direction).isIn("across", "down")
            assertThat(clue.text).isNotNull()
        }
    }

    @Test
    fun `cell array length always equals width times height`() {
        val grid =
            Grid.fromPlacements(
                width = 4,
                height = 6,
                placements =
                    listOf(
                        WordPlacement(Word("CHIEN", "x"), pos(0, 0), Direction.DOWN),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        assertThat(response.cells).hasSize(24)
        assertThat(response.width).isEqualTo(4)
        assertThat(response.height).isEqualTo(6)
    }

    @Test
    fun `cells are emitted in strict row-major order with no skipped indices`() {
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(Word("PAIN", "aliment"), pos(0, 0), Direction.RIGHT),
                    ),
            )

        val response = mapper.toApi(grid, UUID.randomUUID(), Instant.now())

        val expectedPositions =
            (0 until 25).map { i ->
                CellDto.PositionDto(row = i / 5, column = i % 5)
            }
        assertThat(response.cells.map { it.position }).isEqualTo(expectedPositions)
    }
}
