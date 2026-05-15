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
    /**
     * Soft bias in `[0.0, 1.0]` (clamped internally to `[0.0, 0.6]`) that
     * shifts the mean slot length upward (spec §4.5.2). Values above 0.6
     * have no benefit; restart cascades amplify the layout's edges.
     *
     * Defaults to `0.0` — measured on the production corpus, raising the
     * bias to the spec-recommended `0.4` adds at most one long-tail word
     * per grid (e.g. `CORRECTIONS`-class) at 3× p50 latency, while mean
     * slot length is essentially unchanged. Opt in per-call when the
     * extra long word matters more than throughput.
     */
    val longWordBias: Double = 0.0,
    /**
     * Soft penalty applied during seeding to placements that create a
     * neighbouring run of exactly length 2 (spec §4.5.3). The spec
     * recommends `2.0`, but on this corpus measured eventual success
     * rate drops from 100 % (LEN2=0) to 93 % (LEN2=2) and the long
     * tail more than doubles — the penalty starves the density sprinkle
     * once the no-3-in-a-row rule and `BLACK_RATIO=0.24` are in effect.
     * Default `0.0` (effectively disabled); the visible cost is ~3pp
     * more length-2 slots. Opt in per-call when willing to pay the
     * latency hit.
     */
    val lengthTwoPenalty: Double = 0.0,
) {
    init {
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(minWordLength >= 2) { "minWordLength must be at least 2" }
        require(themeLimits.values.all { it >= 0 }) { "theme caps must be non-negative" }
        require(longWordBias in 0.0..1.0) { "longWordBias must be in [0.0, 1.0]" }
        require(lengthTwoPenalty >= 0.0) { "lengthTwoPenalty must be non-negative" }
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
        Themes.COMPASS to 2,
        Themes.GREEK to 2,
        Themes.SIGLE to 2,
    )
