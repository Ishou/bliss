package com.bliss.grid.domain.model

enum class Direction(
    val offset: Position,
) {
    RIGHT(Position(Row(0), Column(1))),
    DOWN(Position(Row(1), Column(0))),
}
