package com.bliss.game.domain

/**
 * Ordered list of grid positions occupied by a clue's answer, starting at
 * [GameClue.start] and walking [GameClue.length] cells in [GameClue.direction].
 *
 * Mots-fléchés answers are contiguous letter cells, so each step advances
 * exactly one row (DOWN) or one column (ACROSS). Block / definition cells
 * never appear inside a clue's word.
 */
fun wordPositions(clue: GameClue): List<Position> {
    val (rowStep, colStep) =
        when (clue.direction) {
            GameClueDirection.ACROSS -> 0 to 1
            GameClueDirection.DOWN -> 1 to 0
        }
    val start = clue.start
    return List(clue.length) { i ->
        Position(start.row + i * rowStep, start.column + i * colStep)
    }
}

/**
 * Every word (as an ordered list of positions) whose answer covers [position].
 * Result order follows [GamePuzzle.clues] so callers can map back to clue
 * metadata. A letter cell at an across × down crossing returns two words; a
 * cell that is not part of any clue returns an empty list.
 */
fun GamePuzzle.wordsContaining(position: Position): List<List<Position>> =
    clues.mapNotNull { clue ->
        val positions = wordPositions(clue)
        if (position in positions) positions else null
    }
