package com.bliss.grid.domain.model

/**
 * Word direction in a mots-fleches grid. Each variant defines:
 * - [startOffset]: offset from the clue cell to the first letter
 * - [step]: offset between consecutive letters
 * - [axis]: whether the word reads horizontally or vertically
 *
 * Standard directions (RIGHT, DOWN) place the first letter adjacent to
 * the clue cell in the same axis. Edge directions (DOWN_RIGHT,
 * RIGHT_DOWN) allow first-column/first-row clues to start words in
 * the perpendicular axis — the clue sits one row above or one column
 * left of the word, giving the generator room to interlock edge cells.
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

    /** Clue above the word in column 0, letters flow right. */
    DOWN_RIGHT(
        startOffset = Position(Row(1), Column(0)),
        step = Position(Row(0), Column(1)),
        axis = WordAxis.HORIZONTAL,
    ),

    /** Clue left of the word in row 0, letters flow down. */
    RIGHT_DOWN(
        startOffset = Position(Row(0), Column(1)),
        step = Position(Row(1), Column(0)),
        axis = WordAxis.VERTICAL,
    ),
}

enum class WordAxis { HORIZONTAL, VERTICAL }
