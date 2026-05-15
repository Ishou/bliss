package com.bliss.grid.domain.generation

import kotlin.math.roundToInt

/**
 * Tuning constants for the bitmask-CSP grid generator. One source of
 * truth; reference these from `GridGenerator`, `BlackCellLayout`,
 * `BitmaskCsp`, `Lexicon` rather than re-declaring per-call defaults.
 *
 * Names and default values track the spec's §13 (Default Parameter Values).
 */
internal object GenerationKnobs {
    /**
     * Black-cell density target for the initial layout pass. The spec
     * recommends `0.22..0.26` for authentic printed-grid look, but on
     * this corpus + dictionary `0.14` measures best on every axis we
     * tested — 100 % eventual success, p50 ≈ 1.3s, mean slot length
     * ~3.98. Higher densities require more black cells in tight
     * positions, which the no-3-in-a-row rule rejects, leaving the
     * CSP with worse-shaped graphs.
     */
    const val DEFAULT_BLACK_RATIO: Double = 0.14

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

    /**
     * Hard cap on restart attempts per [GridGenerator.generate] call.
     * Per spec §13: with Luby budgets, total work stays bounded so we
     * can afford many cheap attempts. The wall-clock deadline is the
     * usual binding constraint; this is just a safety net against
     * pathologically fast per-attempt loops.
     */
    const val MAX_RESTARTS: Int = 2_000

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

    /** Internal cap on `longWordBias`. Past this, restarts make grids worse on average. */
    const val LONG_WORD_BIAS_CAP: Double = 0.6
}

/** Clamp raw `longWordBias` from `[0.0, 1.0]` to the useful range `[0.0, LONG_WORD_BIAS_CAP]`. */
internal fun clampedBias(raw: Double): Double = raw.coerceIn(0.0, GenerationKnobs.LONG_WORD_BIAS_CAP)

/**
 * Target slot-length cap during seeding, scaled by [bias].
 * At `bias = 0` returns `max(minLen + 1, 6)`; at `bias = LONG_WORD_BIAS_CAP`
 * interpolates up toward [lUseful]. Clamped to `[minLen + 1, lUseful]`.
 */
internal fun lTargetFor(
    bias: Double,
    lUseful: Int,
    minLen: Int,
): Int {
    val floor = maxOf(minLen + 1, 6)
    val target = (floor + bias * (lUseful - floor)).roundToInt()
    return target.coerceIn(minLen + 1, lUseful.coerceAtLeast(minLen + 1))
}

/** Minimum "good" neighbour-run length used by the bias-aware density-sprinkle scoring. */
internal fun lMinGood(
    bias: Double,
    lUseful: Int,
    minLen: Int,
): Int = (minLen + bias * (lUseful - 2 - minLen)).roundToInt().coerceAtLeast(minLen)

/** Multiplier on `DEFAULT_BLACK_RATIO`. Higher bias means fewer black cells. */
internal fun densityFactor(bias: Double): Double = 1.0 - 0.35 * bias

/** Probability that perturbation whitens a black cell (vs. blackens a white). */
internal fun whitenProbability(bias: Double): Double = 0.4 + 0.45 * bias

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
