package com.bliss.grid.domain.generation

data class GridConstraints(
    val width: Int,
    val height: Int,
    val minWordLength: Int = 2,
    val targetDensity: Double = 0.6,
) {
    init {
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(minWordLength >= 2) { "minWordLength must be at least 2" }
        require(targetDensity in 0.0..1.0) { "targetDensity must be between 0 and 1" }
    }
}
