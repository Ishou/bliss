// Pure-domain rules for the surface-form ingest pipeline (ADR-0013 §2, §4). No IO.
package com.bliss.grid.domain.lexicon

import kotlin.math.ln

/** §2 filter: drop non-letter chars, drop capitalized first letter, lowercase, dedupe, sort. */
fun filterAndSort(rawLines: Sequence<String>): List<String> =
    rawLines
        .map { it.trim() }
        .filter { it.isNotEmpty() && isAcceptable(it) }
        .map { it.lowercase() }
        .toSortedSet()
        .toList()

/** Pure-letter, non-capitalized — `false` for empties, anything with digits/punct/whitespace, or any uppercase first char. */
fun isAcceptable(surfaceForm: String): Boolean =
    surfaceForm.isNotEmpty() &&
        !surfaceForm[0].isUpperCase() &&
        surfaceForm.all { it.isLetter() }

/**
 * §4: `sigmoid(α·ln(rank) + β·(length − 5))` with `α = 0.15`, `β = 0.20`.
 * Sigmoid is in (0,1); rank = 1, length = 5 lands at 0.5 (calibration target).
 */
fun difficulty(
    rank: Int,
    length: Int,
): Float {
    require(rank >= 1) { "rank must be >= 1, got $rank" }
    require(length >= 1) { "length must be >= 1, got $length" }
    val x = ALPHA * ln(rank.toDouble()) + BETA * (length - LENGTH_PIVOT)
    return (1.0 / (1.0 + Math.exp(-x))).toFloat()
}

private const val ALPHA: Double = 0.15
private const val BETA: Double = 0.20
private const val LENGTH_PIVOT: Int = 5
