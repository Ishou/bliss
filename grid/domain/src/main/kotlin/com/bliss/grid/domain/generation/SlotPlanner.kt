package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Direction

/**
 * Utility helpers for the slot-length policy used by [IntegratedSearch].
 *
 * Historically this object owned a full two-phase planner (`planVariable` +
 * `solveVariable`); that pipeline was replaced by the unified
 * [IntegratedSearch] in the Wave 6 refactor. What remains here is the policy
 * surface the integrated search calls into:
 *
 * - [validLengths] — the canonical set of slot lengths derivable for a given
 *   available run, before any corpus / orphan filtering.
 * - [orphanSafeLengths] — same set, further restricted on the corner arrows
 *   to avoid orphaning a uniquely-owned perpendicular letter cell.
 * - [corpusAwareLengthPolicy] — factory for a `(Int) -> List<Int>` closure
 *   over a [WordRepository] that filters to lengths with sufficient corpus
 *   density.
 */
object SlotPlanner {
    /**
     * Lengths from [validLengths] further filtered for orphan-safety on the corner
     * arrows. The (0, 0) DOWN_RIGHT word (row 1) and (0, 0) RIGHT_DOWN word (col 1)
     * trail at perpendicular columns / rows; if those land at *odd* indices, the
     * perpendicular boundary arrow's uniquely-owned first letter (a row-0 / col-0
     * letter cell) gets deactivated and orphans. Restrict to even-indexed trails.
     *
     * For all other arrows, valid lengths are unconstrained beyond [validLengths] —
     * deactivated perpendicular arrows leave bare letter cells, which the loose
     * mots-fléchés model accepts.
     */
    internal fun orphanSafeLengths(
        arrow: ClueArrow,
        available: Int,
        lengthPolicy: (Int) -> List<Int>,
    ): List<Int> {
        val all = lengthPolicy(available)
        val isCornerRow1 =
            arrow.cluePosition.row.value == 0 &&
                arrow.cluePosition.column.value == 0 &&
                arrow.direction == Direction.DOWN_RIGHT
        val isCornerCol1 =
            arrow.cluePosition.row.value == 0 &&
                arrow.cluePosition.column.value == 0 &&
                arrow.direction == Direction.RIGHT_DOWN
        if (!isCornerRow1 && !isCornerCol1) return all
        // For corner arrows, only L = available (full length, no trail) or even L < available are safe.
        return all.filter { it == available || it % 2 == 0 }
    }

    /**
     * Lengths in `{M, M-1} ∪ [2, M-3]`, returned in **strict descending order**
     * so the integrated search tries the longest viable length per arrow first.
     * M-2 is forbidden — it leaves exactly 1 trailing cell after the trailing
     * clue, which can't form a 2+ letter sub-word and would orphan that cell.
     *
     * For small M:
     *   M=2 → [2]                        (only full)
     *   M=3 → [3, 2]                     (full or M-1)
     *   M=4 → [4, 3]                     (full and M-1; M-2=2 forbidden)
     *   M=5 → [5, 4, 2]                  (M-3 = 2 included)
     *   M=10 → [10, 9, 7, 6, 5, 4, 3, 2] (everything except M-2 = 8)
     */
    internal fun validLengths(available: Int): List<Int> {
        require(available >= 2)
        val result = ArrayList<Int>(available)
        result += available
        if (available >= 3) result += available - 1
        for (l in (available - 3) downTo 2) result += l
        return result
    }

    /**
     * Corpus-aware length policy: filters [validLengths] to lengths whose
     * corpus count is at least [minCorpus]. Falls back to the full
     * [validLengths] list when filtering would leave nothing — better to try a
     * sparse length than to abandon the plan entirely.
     *
     * Rationale: a 10-letter slot whose pattern has ~5 corpus matches forces
     * the integrated search into a doomed CSP. Corpus-aware filtering keeps
     * commitments to lengths where the corpus offers a wide branching factor.
     *
     * The returned function is a stateless closure over the repository — safe
     * to reuse across calls, no caching needed (the underlying
     * [WordRepository.countByLength] is O(1) on indexed adapters).
     */
    fun corpusAwareLengthPolicy(
        repository: WordRepository,
        minCorpus: Int = DEFAULT_MIN_CORPUS,
    ): (Int) -> List<Int> =
        { available ->
            val all = validLengths(available)
            val dense = all.filter { repository.countByLength(it) >= minCorpus }
            if (dense.isNotEmpty()) dense else all
        }

    /**
     * Default floor for [corpusAwareLengthPolicy]. Chosen so that on a
     * 100k-word French corpus, lengths 2..9 typically pass and lengths 10+
     * may be excluded — matching the empirical "long slots dominate the fat
     * tail" finding from the Wave 1 25-gen baseline.
     */
    const val DEFAULT_MIN_CORPUS: Int = 100
}
