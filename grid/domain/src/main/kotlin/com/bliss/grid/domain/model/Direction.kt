package com.bliss.grid.domain.model

/**
 * Word direction in a mots-fleches grid. Each variant defines:
 * - [startOffset]: offset from the clue cell to the first letter
 * - [step]: offset between consecutive letters
 * - [axis]: whether the word reads horizontally or vertically
 */
enum class Direction(
    val startOffset: Position,
    val step: Position,
    val axis: WordAxis,
) {
    /** Clue left of the word, letters flow right. */
    RIGHT(
        startOffset = Position(Row(0), Column(1)),
        step = Position(Row(0), Column(1)),
        axis = WordAxis.HORIZONTAL,
    ),

    /** Clue above the word, letters flow down. */
    DOWN(
        startOffset = Position(Row(1), Column(0)),
        step = Position(Row(1), Column(0)),
        axis = WordAxis.VERTICAL,
    ),
}

enum class WordAxis { HORIZONTAL, VERTICAL }
