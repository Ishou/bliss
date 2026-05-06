package com.bliss.grid.domain.generation

/**
 * Per-generation perf metrics. Optional sink passed through the generation
 * pipeline; production callers pass `null` and pay zero cost. The metrics
 * surface where the time goes — skeleton vs slot-plan vs CSP fill — and
 * count branching effort (backtracks, repo lookups, MRV scans).
 *
 * Intentionally a mutable holder, not a value type — the search loop writes
 * incrementally and the hot path runs millions of times per timeout. Each
 * field is a primitive (Long/Int) so writes are JIT-friendly and the GC
 * sees nothing.
 */
class GenerationMetrics {
    /** Wall time of the deterministic boundary skeleton phase. */
    var skeletonMs: Long = 0

    /** Wall time of the slot-planner backtracking phase. */
    var slotPlanMs: Long = 0

    /** Number of slot-planner backtrack steps (undo operations). */
    var slotPlanBacktracks: Int = 0

    /** Wall time of the CSP fill phase (word backtracking). */
    var fillMs: Long = 0

    /**
     * Number of fill-phase backtrack steps (`Undo` block in
     * [SkeletonFiller.search]). Proportional to the size of the search tree
     * actually explored.
     */
    var fillBacktracks: Int = 0

    /**
     * Number of `findByLengthAndPattern` calls. Each MRV scan calls it once
     * per unassigned slot, so this scales as
     * `Σ unassigned-slots-at-each-search-node`.
     */
    var fillRepoCalls: Int = 0

    /**
     * Domain size at the very first MRV-picked slot (root of the search
     * tree). A small value means the corpus + skeleton agree quickly; a
     * large value means the search has a wide branching factor at the root.
     */
    var fillFirstSlotDomainSize: Int = -1

    /** Whether the generation completed within the timeout. */
    var succeeded: Boolean = false

    /** Total grids attempted by the caller (only set by callers that retry). */
    var attempts: Int = 1
}
