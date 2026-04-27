package com.bliss.grid.domain.model

data class WordPlacement(
    val word: Word,
    val cluePosition: Position,
    val direction: Direction,
) {
    fun letterPositions(): List<Pair<Position, Char>> {
        val startRow = cluePosition.row.value + direction.startOffset.row.value
        val startCol = cluePosition.column.value + direction.startOffset.column.value
        val dr = direction.step.row.value
        val dc = direction.step.column.value
        return word.text.mapIndexed { i, ch ->
            Position(Row(startRow + dr * i), Column(startCol + dc * i)) to ch
        }
    }
}
