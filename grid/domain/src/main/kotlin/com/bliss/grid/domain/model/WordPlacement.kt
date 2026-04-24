package com.bliss.grid.domain.model

data class WordPlacement(
    val word: Word,
    val cluePosition: Position,
    val direction: Direction,
) {
    fun letterPositions(): List<Pair<Position, Char>> {
        val (dr, dc) = direction.offset.row.value to direction.offset.column.value
        val startRow = cluePosition.row.value + dr
        val startCol = cluePosition.column.value + dc
        return word.text.mapIndexed { i, ch ->
            Position(Row(startRow + dr * i), Column(startCol + dc * i)) to ch
        }
    }
}
