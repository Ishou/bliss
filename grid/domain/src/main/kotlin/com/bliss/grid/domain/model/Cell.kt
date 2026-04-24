package com.bliss.grid.domain.model

sealed interface Cell

data class LetterCell(
    val letter: Char,
) : Cell {
    init {
        require(letter in 'A'..'Z') { "LetterCell letter must be uppercase A-Z, was '$letter'" }
    }
}

data class ClueCell(
    val clues: List<Clue>,
) : Cell {
    init {
        require(clues.isNotEmpty()) { "ClueCell must have at least one clue" }
        require(clues.size <= 2) { "ClueCell may hold at most 2 clues, was ${clues.size}" }
        require(clues.map { it.direction }.toSet().size == clues.size) {
            "ClueCell may not contain two clues with the same direction"
        }
    }
}

data object EmptyCell : Cell
