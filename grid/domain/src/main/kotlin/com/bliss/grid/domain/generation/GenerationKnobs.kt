package com.bliss.grid.domain.generation

/**
 * Tuning constants for the bitmask-CSP grid generator. One source of
 * truth; reference these from `GridGenerator`, `BlackCellLayout`,
 * `BitmaskCsp`, `Lexicon` rather than re-declaring per-call defaults.
 *
 * Names and default values track the spec's §13 (Default Parameter Values).
 */
internal object GenerationKnobs {
    /** Black-cell density target for the initial layout pass. */
    const val DEFAULT_BLACK_RATIO: Double = 0.18

    /**
     * Target slot length cap for the initial layout pass. Lower than
     * `usefulLength` (the longest length with a useful corpus) to give
     * the search headroom — at default 9, the corpus has thousands of
     * words even with intersection constraints.
     */
    const val DEFAULT_L_TARGET: Int = 9

    /**
     * Minimum corpus count for a length to be considered "fillable" when
     * computing `Lexicon.usefulLength`. Below this, lengths are too sparse
     * to support intersected CSP search.
     */
    const val L_USEFUL_FLOOR: Int = 50

    /** Base backtrack budget per restart, multiplied by `luby(attempt + 1)`. */
    const val BASE_BUDGET_BACKTRACKS: Int = 200

    /** Maximum number of restart attempts before declaring failure. */
    const val MAX_RESTARTS: Int = 60

    /**
     * Fraction of grid cells to toggle per perturbation step. ~4% is
     * enough to break a locally-stuck layout without disturbing the
     * surrounding successful structure.
     */
    const val PERTURB_INTENSITY: Double = 0.04

    /**
     * Every Nth consecutive failure triggers a full re-seed of the
     * black-cell layout (vs. targeted perturbation). Escapes pathological
     * layouts that local perturbation can't fix.
     */
    const val CONSEC_RESEED: Int = 12

    /**
     * Window of candidate words to score with LCV (least-constraining-
     * value). Full LCV is O(|domain| × crossings × domain ops); the
     * sampled window keeps value ordering cost bounded while preserving
     * most of the heuristic's benefit.
     */
    const val LCV_SAMPLE_SIZE: Int = 32

    /** Number of "hot" (smallest-domain) slots fed to targeted perturbation. */
    const val HOT_SLOTS_FOR_PERTURB: Int = 5

    /** Maximum word length the lexicon will index. Bigger lengths are dropped at construction. */
    const val LEXICON_MAX_LEN: Int = 18
}

/**
 * Per-attempt soft time budget in seconds, piecewise by grid area
 * (`width × height`). Caps a single attempt so a doomed layout doesn't
 * burn the whole overall `timeoutMs`. From spec §8.3.
 */
internal fun perAttemptSeconds(area: Int): Double =
    when {
        area <= 200 -> 2.0
        area <= 600 -> 5.0
        area <= 1500 -> 10.0
        else -> 15.0
    }
