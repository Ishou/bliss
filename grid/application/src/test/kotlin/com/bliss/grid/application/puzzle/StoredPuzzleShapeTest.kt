package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test
import java.time.Instant

class StoredPuzzleShapeTest {
    @Test
    fun `StoredPuzzle exposes totalLetterCells derived from the grid`() {
        // A single 5-letter word placed DOWN_RIGHT contributes 5 letter cells
        // (its `letterPositions()` walks the diagonal one step per character).
        val word = Word(text = "ABCDE", definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        val grid = Grid.fromPlacements(width = 6, height = 6, placements = listOf(placement))

        val stored =
            StoredPuzzle(
                grid = grid,
                title = "t",
                language = "fr",
                hintsAllowed = 3,
                createdAt = Instant.EPOCH,
            )

        assertThat(stored.totalLetterCells).isEqualTo(5)
    }
}
