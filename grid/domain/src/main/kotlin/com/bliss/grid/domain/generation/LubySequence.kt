package com.bliss.grid.domain.generation

/**
 * Luby restart sequence (Luby/Sinclair/Zuckerman 1993).
 *
 * Returns the [i]-th element of `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, …`.
 *
 * The sequence is provably near-optimal for Las Vegas algorithms with
 * unknown runtime distribution: it minimises worst-case wasted work
 * within a factor of `O(log n)` of the best fixed schedule. Used by
 * [GridGenerator] to choose per-attempt backtrack budgets when restarting
 * the bitmask CSP search.
 *
 * @param i a 1-based index
 * @return the i-th Luby number
 */
internal fun luby(i: Int): Int {
    require(i >= 1) { "luby index must be ≥ 1, was $i" }
    // Iterative formulation: walk powers-of-two until we land on i.
    var n = i
    var k = 1
    while (true) {
        val upper = (1 shl k) - 1
        if (n == upper) return 1 shl (k - 1)
        val lower = 1 shl (k - 1)
        if (n in lower until upper) {
            // Recursive case unrolled: n - lower + 1 is the new index.
            n = n - lower + 1
            k = 1
            continue
        }
        k++
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable")
}
