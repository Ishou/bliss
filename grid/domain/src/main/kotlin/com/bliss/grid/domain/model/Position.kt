package com.bliss.grid.domain.model

@JvmInline
value class Row(
    val value: Int,
) {
    init {
        require(value >= 0) { "Row must be non-negative, was $value" }
    }
}

@JvmInline
value class Column(
    val value: Int,
) {
    init {
        require(value >= 0) { "Column must be non-negative, was $value" }
    }
}

data class Position(
    val row: Row,
    val column: Column,
)
