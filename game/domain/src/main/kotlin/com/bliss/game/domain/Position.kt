package com.bliss.game.domain

data class Position(
    val row: Int,
    val column: Int,
) {
    init {
        require(row >= 0) { "Position row must be non-negative, was $row" }
        require(column >= 0) { "Position column must be non-negative, was $column" }
    }
}

data class GridConfig(
    val width: Int,
    val height: Int,
) {
    init {
        require(width in MIN..MAX) { "GridConfig width must be in $MIN..$MAX, was $width" }
        require(height in MIN..MAX) { "GridConfig height must be in $MIN..$MAX, was $height" }
    }

    companion object {
        const val MIN = 5
        const val MAX = 15
    }
}
