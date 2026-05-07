package com.bliss.grid.domain.generation

data class GridConstraints(
    val width: Int,
    val height: Int,
    val minWordLength: Int = 2,
    /**
     * Per-grid caps on themed words. Key is a [Themes] constant; value is
     * the maximum number of slots that may be filled with words carrying
     * that theme. A theme not in the map is uncapped. A theme with cap 0
     * is banned. Default = [DEFAULT_THEME_LIMITS] — keeps short specialty
     * words (chem symbols, Roman numerals, …) from dominating the grid.
     */
    val themeLimits: Map<String, Int> = DEFAULT_THEME_LIMITS,
) {
    init {
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(minWordLength >= 2) { "minWordLength must be at least 2" }
        require(themeLimits.values.all { it >= 0 }) { "theme caps must be non-negative" }
    }
}

/**
 * Default per-grid theme caps. Tuned against the production corpus so a
 * typical 10×10 grid produces at most ~6 themed words across all
 * categories — the rest of the slots are regular vocabulary.
 *
 * Conservative starting point; loosen / tighten in later iters as we
 * eyeball the generated grids.
 */
val DEFAULT_THEME_LIMITS: Map<String, Int> =
    mapOf(
        Themes.ROMAN to 1,
        Themes.CHEM to 2,
        Themes.ABBREV to 2,
        Themes.COUNTRY to 1,
        Themes.INTERJECTION to 1,
        Themes.NOTE to 1,
        Themes.UNIT to 1,
        Themes.COMPASS to 1,
        Themes.GREEK to 2,
    )
