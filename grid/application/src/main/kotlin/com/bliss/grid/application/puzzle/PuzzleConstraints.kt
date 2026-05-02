package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.GridConstraints

const val PUZZLE_WIDTH: Int = 10
const val PUZZLE_HEIGHT: Int = 10
const val PUZZLE_MIN_WORD_LENGTH: Int = 2

/**
 * Inclusive bounds for caller-supplied puzzle dimensions on
 * `GET /v1/puzzles/{id}?width=N&height=N`. The lower bound keeps the CSP
 * solver's vocabulary needs reasonable; the upper bound keeps generation
 * time within the per-request attempt budget. Mirrors the `PuzzleWidth` /
 * `PuzzleHeight` parameter schemas in `grid/api/openapi.yaml` — change
 * both together.
 */
const val PUZZLE_MIN_DIMENSION: Int = 5
const val PUZZLE_MAX_DIMENSION: Int = 15

fun defaultPuzzleConstraints(): GridConstraints =
    GridConstraints(
        width = PUZZLE_WIDTH,
        height = PUZZLE_HEIGHT,
        minWordLength = PUZZLE_MIN_WORD_LENGTH,
    )
