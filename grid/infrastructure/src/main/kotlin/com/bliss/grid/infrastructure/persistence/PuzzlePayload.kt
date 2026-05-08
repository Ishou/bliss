package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import com.bliss.grid.domain.model.WordPlacement
import kotlinx.serialization.Serializable

/**
 * JSONB-friendly shape for the `puzzles.payload` column. We store the
 * placement list rather than the materialized cell map because
 * `Grid.fromPlacements(width, height, placements)` already reconstructs the
 * cells deterministically — duplicating cell state in the payload would
 * just be a redundancy hazard.
 *
 * Round-trip: domain Grid → [fromGrid] → JSON → [toGrid] → domain Grid,
 * with the resulting Grid `==` the original modulo the cells map order
 * (which is irrelevant since Grid.cells is a Map<Position, Cell>).
 */
@Serializable
data class PuzzlePayload(
    val width: Int,
    val height: Int,
    val placements: List<SerializedPlacement>,
) {
    @Serializable
    data class SerializedPlacement(
        val wordText: String,
        val wordLemma: String,
        val clues: List<SerializedClue>,
        val chosenClueIndex: Int,
        val cluePositionRow: Int,
        val cluePositionColumn: Int,
        val direction: SerializedDirection,
    )

    @Serializable
    data class SerializedClue(
        val text: String,
        val theme: String? = null,
    )

    @Serializable
    enum class SerializedDirection {
        RIGHT,
        DOWN,
        DOWN_RIGHT,
        RIGHT_DOWN,
    }

    fun toGrid(): Grid {
        val placementsList =
            placements.map { sp ->
                val word =
                    Word(
                        text = sp.wordText,
                        clues = sp.clues.map { WordClue(it.text, it.theme) },
                        lemma = sp.wordLemma,
                    )
                require(sp.chosenClueIndex in word.clues.indices) {
                    "chosenClueIndex ${sp.chosenClueIndex} out of range for ${word.clues.size} clues"
                }
                WordPlacement(
                    word = word,
                    cluePosition = Position(Row(sp.cluePositionRow), Column(sp.cluePositionColumn)),
                    direction = sp.direction.toDomain(),
                    chosenClue = word.clues[sp.chosenClueIndex],
                )
            }
        return Grid.fromPlacements(width, height, placementsList)
    }

    companion object {
        fun fromGrid(grid: Grid): PuzzlePayload =
            PuzzlePayload(
                width = grid.width,
                height = grid.height,
                placements =
                    grid.placements.map { p ->
                        val idx =
                            p.word.clues
                                .indexOf(p.chosenClue)
                                .coerceAtLeast(0)
                        SerializedPlacement(
                            wordText = p.word.text,
                            wordLemma = p.word.lemma,
                            clues = p.word.clues.map { SerializedClue(it.text, it.theme) },
                            chosenClueIndex = idx,
                            cluePositionRow = p.cluePosition.row.value,
                            cluePositionColumn = p.cluePosition.column.value,
                            direction = p.direction.toSerialized(),
                        )
                    },
            )
    }
}

private fun Direction.toSerialized(): PuzzlePayload.SerializedDirection =
    when (this) {
        Direction.RIGHT -> PuzzlePayload.SerializedDirection.RIGHT
        Direction.DOWN -> PuzzlePayload.SerializedDirection.DOWN
        Direction.DOWN_RIGHT -> PuzzlePayload.SerializedDirection.DOWN_RIGHT
        Direction.RIGHT_DOWN -> PuzzlePayload.SerializedDirection.RIGHT_DOWN
    }

private fun PuzzlePayload.SerializedDirection.toDomain(): Direction =
    when (this) {
        PuzzlePayload.SerializedDirection.RIGHT -> Direction.RIGHT
        PuzzlePayload.SerializedDirection.DOWN -> Direction.DOWN
        PuzzlePayload.SerializedDirection.DOWN_RIGHT -> Direction.DOWN_RIGHT
        PuzzlePayload.SerializedDirection.RIGHT_DOWN -> Direction.RIGHT_DOWN
    }
