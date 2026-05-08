package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
import java.util.concurrent.ConcurrentHashMap

class ValidatePuzzleUseCaseTest {
    private val grid =
        Grid.fromPlacements(
            width = 3,
            height = 3,
            placements =
                listOf(
                    WordPlacement(
                        Word(text = "OR", definition = "metal"),
                        Position(Row(0), Column(0)),
                        Direction.RIGHT,
                    ),
                ),
        )

    @Test
    fun `solved=true and incorrectCells empty when every letter is correct`() {
        val (puzzleId, store) = stored()
        // OR is placed at (0,1)='O' and (0,2)='R' (clue at (0,0), Direction.RIGHT).
        val outcome =
            ValidatePuzzleUseCase(store).execute(
                puzzleId,
                listOf(
                    FilledCellInput(0, 1, "O"),
                    FilledCellInput(0, 2, "R"),
                ),
            )
        assertThat(outcome).isInstanceOf(ValidatePuzzleOutcome.Result::class)
        val result = outcome as ValidatePuzzleOutcome.Result
        assertThat(result.solved).isEqualTo(true)
        assertThat(result.incorrectCells).isEmpty()
    }

    @Test
    fun `unfilled cells appear in incorrectCells - solved=false`() {
        val (puzzleId, store) = stored()
        val outcome = ValidatePuzzleUseCase(store).execute(puzzleId, emptyList())
        val result = outcome as ValidatePuzzleOutcome.Result
        assertThat(result.solved).isEqualTo(false)
        assertThat(result.incorrectCells)
            .containsExactlyInAnyOrder(
                Position(Row(0), Column(1)),
                Position(Row(0), Column(2)),
            )
    }

    @Test
    fun `wrong letter at correct position is marked incorrect - canonical letter not leaked`() {
        val (puzzleId, store) = stored()
        val outcome =
            ValidatePuzzleUseCase(store).execute(
                puzzleId,
                listOf(
                    FilledCellInput(0, 1, "O"),
                    FilledCellInput(0, 2, "X"), // wrong; canonical is R
                ),
            )
        val result = outcome as ValidatePuzzleOutcome.Result
        assertThat(result.solved).isEqualTo(false)
        assertThat(result.incorrectCells).containsExactlyInAnyOrder(Position(Row(0), Column(2)))
    }

    @Test
    fun `out-of-range position returns RequestInvalid`() {
        val (puzzleId, store) = stored()
        val outcome =
            ValidatePuzzleUseCase(store).execute(
                puzzleId,
                listOf(FilledCellInput(99, 99, "A")),
            )
        assertThat(outcome).isInstanceOf(ValidatePuzzleOutcome.RequestInvalid::class)
    }

    @Test
    fun `position pointing at non-letter cell returns RequestInvalid`() {
        val (puzzleId, store) = stored()
        // (0,0) is the clue cell, not a letter cell.
        val outcome =
            ValidatePuzzleUseCase(store).execute(
                puzzleId,
                listOf(FilledCellInput(0, 0, "A")),
            )
        assertThat(outcome).isInstanceOf(ValidatePuzzleOutcome.RequestInvalid::class)
    }

    @Test
    fun `PuzzleNotFound when store is empty`() {
        val store = fakeStore(emptyMap())
        val outcome = ValidatePuzzleUseCase(store).execute(UUID.randomUUID(), emptyList())
        assertThat(outcome).isInstanceOf(ValidatePuzzleOutcome.PuzzleNotFound::class)
    }

    private fun stored(): Pair<UUID, PuzzleRepository> {
        val id = UUID.randomUUID()
        val stored =
            StoredPuzzle(
                grid = grid,
                title = "T",
                language = "fr",
                hintsAllowed = 3,
                createdAt = Instant.parse("2026-04-24T15:30:00Z"),
            )
        return id to fakeStore(mapOf(id to stored))
    }

    private fun fakeStore(seed: Map<UUID, StoredPuzzle>): PuzzleRepository {
        val store = ConcurrentHashMap(seed)
        return object : PuzzleRepository {
            override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

            override fun getOrCompute(
                puzzleId: UUID,
                factory: () -> StoredPuzzle?,
            ): StoredPuzzle? = store[puzzleId] ?: factory()?.also { store[puzzleId] = it }
        }
    }
}
