package com.bliss.grid.domain.model

data class WordPlacement(
    val word: Word,
    val cluePosition: Position,
    val direction: Direction,
    /**
     * The clue text + theme actually shown for this placement. Picked
     * by `SkeletonFiller` at fill time to respect per-grid theme caps:
     * if a word has multiple clues, the one whose theme isn't already
     * at its cap wins. Defaults to the word's first clue for callers
     * that build placements without going through the filler (tests,
     * legacy use cases) — those lose the diversity logic but keep
     * working.
     */
    val chosenClue: WordClue = word.clues.first(),
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
