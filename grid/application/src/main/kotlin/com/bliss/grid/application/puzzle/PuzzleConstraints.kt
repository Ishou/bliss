package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.GridConstraints

const val PUZZLE_WIDTH: Int = 15
const val PUZZLE_HEIGHT: Int = 12
const val PUZZLE_MIN_WORD_LENGTH: Int = 2

fun defaultPuzzleConstraints(): GridConstraints =
    GridConstraints(
        width = PUZZLE_WIDTH,
        height = PUZZLE_HEIGHT,
        minWordLength = PUZZLE_MIN_WORD_LENGTH,
    )
