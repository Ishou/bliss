package com.bliss.grid.domain.generation

import kotlin.math.max
import kotlin.random.Random

/**
 * Generates and perturbs the BLACK-cell skeleton of a mots-fléchés grid.
 *
 * **Invariant: every BLACK cell is FUNCTIONAL** (hosts at least one
 * arrow — either RIGHT into a horizontal slot starting at `(r, c+1)`,
 * DOWN into a vertical slot starting at `(r+1, c)`, DOWN_RIGHT into a
 * row-(r+1) horizontal slot when `c == 0`, or RIGHT_DOWN into a col-(c+1)
 * vertical slot when `r == 0`). "Decorative" / "dead" black cells (with
 * no arrow) are never produced. See [isFunctional].
 *
 * The seed procedure is six passes (cf. spec §4.4):
 *  1+2. Cap horizontal/vertical runs at `lTarget`.
 *  3.   Re-cap any run still > `lUseful` (rare; bounded loop).
 *  4.   Repair orphan whites by *whitening* neighbours rather than
 *       blackening the orphan. Whitening avoids the dead-cell cascade.
 *  5.   Density sprinkle to `blackRatio`, gated by [canPlaceBlack].
 *  6.   Dead-cell cleanup: whiten any black that became non-functional
 *       via downstream changes.
 *
 * When [seed]'s `protectedCells` parameter is non-empty, a feature-aware
 * boundary is laid down first: ONE anchor slot — either horizontal
 * (cells `(0, 1..hFeat)`) or vertical (cells `(1..vFeat, 0)`) — is
 * reserved as the printed-mots-fléchés "anchor" slot, terminated by a
 * black at `(0, hFeat+1)` or `(vFeat+1, 0)`. Both directions cannot be
 * active simultaneously under the current `ClueCell` 2-clue cap. Every
 * later helper skips protected cells.
 */
internal object BlackCellLayout {
    fun seed(
        width: Int,
        height: Int,
        minLen: Int,
        lTarget: Int,
        lUseful: Int,
        blackRatio: Double,
        random: Random,
        protectedCells: Set<Pair<Int, Int>> = emptySet(),
        lMinGood: Int = minLen,
        lengthTwoPenalty: Double = 0.0,
    ): CellArray {
        require(minLen >= 2) { "minLen must be ≥ 2, was $minLen" }
        require(lTarget in minLen..lUseful) { "lTarget=$lTarget not in [minLen=$minLen, lUseful=$lUseful]" }
        require(blackRatio in 0.0..0.5) { "blackRatio $blackRatio out of [0.0, 0.5]" }

        val cells = CellArray(width, height)
        if (protectedCells.isEmpty()) {
            seedBoundarySkeleton(cells, width, height)
        } else {
            seedBoundaryWithFeatures(cells, width, height, protectedCells, minLen)
        }

        // Passes 1+2: cap long runs at lTarget by midpoint-splitting.
        repeat(8) {
            var changed = false
            for (r in 0 until height) {
                if (capLongHorizontalRuns(cells, r, minLen, lTarget, protectedCells, lengthTwoPenalty, random)) {
                    changed = true
                }
            }
            for (c in 0 until width) {
                if (capLongVerticalRuns(cells, c, minLen, lTarget, protectedCells, lengthTwoPenalty, random)) {
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        // Pass 3: final guard at lUseful.
        repeat(3) {
            var changed = false
            for (r in 0 until height) {
                if (capLongHorizontalRuns(cells, r, minLen, lUseful, protectedCells, lengthTwoPenalty, random)) {
                    changed = true
                }
            }
            for (c in 0 until width) {
                if (capLongVerticalRuns(cells, c, minLen, lUseful, protectedCells, lengthTwoPenalty, random)) {
                    changed = true
                }
            }
            if (!changed) return@repeat
        }

        // Pass 4: repair orphan whites by whitening a neighbour rather
        // than blackening the orphan (which would always produce a dead
        // black).
        repairOrphanWhites(cells, minLen, lUseful, protectedCells)

        // Pass 5: density sprinkle, gated by canPlaceBlack.
        if (lMinGood > minLen || lengthTwoPenalty > 0.0) {
            densitySprinkleScored(
                cells = cells,
                minLen = minLen,
                blackRatio = blackRatio,
                lMinGood = lMinGood,
                lengthTwoPenalty = lengthTwoPenalty,
                protectedCells = protectedCells,
                random = random,
            )
        } else {
            densitySprinkle(cells, minLen, blackRatio, protectedCells, random)
        }

        // Pass 4 again — sprinkling may have introduced new orphans.
        repairOrphanWhites(cells, minLen, lUseful, protectedCells)

        // Pass 6: dead-cell cleanup.
        removeDeadBlacks(cells, minLen, lUseful)

        return cells
    }

    /**
     * Perturb [cells] by adding/removing a few cells. Every black-cell
     * addition goes through [canPlaceBlack] (which enforces the
     * no-dead-black invariant). Whitenings revert if they introduce an
     * over-long run OR a dead neighbour. The final cleanup re-runs
     * dead-cell elimination so callers always see invariant-clean state.
     */
    fun perturb(
        cells: CellArray,
        minLen: Int,
        lUseful: Int,
        hotCells: List<Pair<Int, Int>>,
        intensity: Double,
        random: Random,
        protectedCells: Set<Pair<Int, Int>> = emptySet(),
        whitenProbability: Double = 0.4,
    ) {
        val area = cells.width * cells.height
        var moves = max(2, (intensity * area).toInt())

        for ((r, c) in hotCells.take(2)) {
            if (moves <= 0) break
            if ((r to c) in protectedCells) continue
            if (!cells.isBlack(r, c) && canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
                moves--
            }
        }

        var attempts = 0
        while (moves > 0 && attempts < area * 4) {
            attempts++
            val r = random.nextInt(cells.height)
            val c = random.nextInt(cells.width)
            if (r == 0 && c == 0) continue // corner must stay BLACK
            if ((r to c) in protectedCells) continue
            if (cells.isBlack(r, c)) {
                if (random.nextDouble() < whitenProbability &&
                    tryWhitenSafely(cells, r, c, minLen, lUseful)
                ) {
                    moves--
                }
            } else if (canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
                moves--
            }
        }

        repairOrphanWhites(cells, minLen, lUseful, protectedCells)
        removeDeadBlacks(cells, minLen, lUseful)
    }

    // ---- Predicates ----

    /**
     * A BLACK cell at `(r, c)` is **functional** iff it hosts at least
     * one arrow into a slot of length ≥ [minLen]:
     *  - **RIGHT**: horizontal slot starts at `(r, c+1)`.
     *  - **DOWN**: vertical slot starts at `(r+1, c)`.
     *  - **DOWN_RIGHT** (`c == 0` corner): horizontal slot starts at
     *    `(r+1, 0)`. This is how [SlotRegistry] resolves clue cells
     *    for slots beginning at column 0.
     *  - **RIGHT_DOWN** (`r == 0` corner): vertical slot starts at
     *    `(0, c+1)`. Symmetric to DOWN_RIGHT for row 0.
     *
     * Returns false for non-BLACK cells.
     */
    fun isFunctional(
        cells: CellArray,
        r: Int,
        c: Int,
        minLen: Int,
    ): Boolean {
        if (!cells.isBlack(r, c)) return false
        if (c + 1 < cells.width &&
            !cells.isBlack(r, c + 1) &&
            forwardRunHorizontal(cells, r, c + 1) >= minLen
        ) {
            return true
        }
        if (r + 1 < cells.height &&
            !cells.isBlack(r + 1, c) &&
            forwardRunVertical(cells, r + 1, c) >= minLen
        ) {
            return true
        }
        if (c == 0 &&
            r + 1 < cells.height &&
            !cells.isBlack(r + 1, 0) &&
            forwardRunHorizontal(cells, r + 1, 0) >= minLen
        ) {
            return true
        }
        if (r == 0 &&
            c + 1 < cells.width &&
            !cells.isBlack(0, c + 1) &&
            forwardRunVertical(cells, 0, c + 1) >= minLen
        ) {
            return true
        }
        return false
    }

    /**
     * Predicate gating every black-cell placement. Spec §4.3 — three
     * checks:
     *  - **Check 1**: every adjacent white run (in the affected axis)
     *    remains ≥ minLen.
     *  - **Check 2**: the new black is itself functional.
     *  - **Check 3**: no existing neighbouring black becomes dead as a
     *    side effect of this placement.
     *
     * Tentatively mutates [cells] and reverts before returning.
     */
    fun canPlaceBlack(
        cells: CellArray,
        r: Int,
        c: Int,
        minLen: Int,
    ): Boolean {
        if (cells.isBlack(r, c)) return false
        val prior = cells.get(r, c)
        cells.set(r, c, CellArray.BLACK)
        try {
            // Check 1: adjacent white runs in the affected axis remain long enough.
            for (cc in intArrayOf(c - 1, c + 1)) {
                if (cc !in 0 until cells.width) continue
                if (cells.isBlack(r, cc)) continue
                if (runLengthHorizontal(cells, r, cc) < minLen) return false
            }
            for (rr in intArrayOf(r - 1, r + 1)) {
                if (rr !in 0 until cells.height) continue
                if (cells.isBlack(rr, c)) continue
                if (runLengthVertical(cells, rr, c) < minLen) return false
            }
            // Check 2: the new black must itself be functional.
            if (!isFunctional(cells, r, c, minLen)) return false
            // Check 3: existing neighbouring blacks must remain functional.
            // Placing BLACK at (r, c) can harm only (r, c-1) (loses its
            // RIGHT-arrow target) and (r-1, c) (loses its DOWN-arrow target).
            if (c - 1 >= 0 && cells.isBlack(r, c - 1) && !isFunctional(cells, r, c - 1, minLen)) return false
            if (r - 1 >= 0 && cells.isBlack(r - 1, c) && !isFunctional(cells, r - 1, c, minLen)) return false
            // Check 4: no 3-in-a-row block of black cells. Spec §4.1 C2 —
            // pairs are fine, triples are visually heavy and never appear
            // in printed mots fléchés.
            if (countBlackRun(cells, r, c, dr = 0, dc = 1) >= 3) return false
            if (countBlackRun(cells, r, c, dr = 1, dc = 0) >= 3) return false
            return true
        } finally {
            cells.set(r, c, prior)
        }
    }

    /**
     * Length of the maximal black run through `(r, c)` in direction `(dr, dc)`.
     * `(r, c)` must be BLACK when called; counts itself.
     */
    private fun countBlackRun(
        cells: CellArray,
        r: Int,
        c: Int,
        dr: Int,
        dc: Int,
    ): Int {
        var count = 1
        var rr = r - dr
        var cc = c - dc
        while (rr in 0 until cells.height && cc in 0 until cells.width && cells.isBlack(rr, cc)) {
            count++
            rr -= dr
            cc -= dc
        }
        rr = r + dr
        cc = c + dc
        while (rr in 0 until cells.height && cc in 0 until cells.width && cells.isBlack(rr, cc)) {
            count++
            rr += dr
            cc += dc
        }
        return count
    }

    /**
     * Try whitening BLACK at `(r, c)`. Reverts (returns false) if
     *  - the resulting white cell merges into an over-long run (> [lUseful]), OR
     *  - any previously-adjacent black becomes dead.
     */
    private fun tryWhitenSafely(
        cells: CellArray,
        r: Int,
        c: Int,
        minLen: Int,
        lUseful: Int,
    ): Boolean {
        if (r == 0 && c == 0) return false
        if (!cells.isBlack(r, c)) return false
        cells.set(r, c, CellArray.EMPTY)
        val hRun = runLengthHorizontal(cells, r, c)
        val vRun = runLengthVertical(cells, r, c)
        if (hRun > lUseful || vRun > lUseful) {
            cells.set(r, c, CellArray.BLACK)
            return false
        }
        val nbrs = listOf(r to (c - 1), r to (c + 1), (r - 1) to c, (r + 1) to c)
        for ((nr, nc) in nbrs) {
            if (nr !in 0 until cells.height || nc !in 0 until cells.width) continue
            if (!cells.isBlack(nr, nc)) continue
            if (nr == 0 && nc == 0) continue // corner is structurally functional by convention
            if (!isFunctional(cells, nr, nc, minLen)) {
                cells.set(r, c, CellArray.BLACK)
                return false
            }
        }
        return true
    }

    // ---- Layout passes ----

    /**
     * Pass 4: repair every orphan-white (a white cell with NO white
     * cardinal neighbour) by whitening one of its black neighbours
     * (preferred), or as last resort by blackening the orphan ONLY if
     * the resulting black would itself be functional. If neither path
     * works the orphan stays — the [SlotRegistry] validator will reject
     * the layout and the driver will perturb.
     */
    private fun repairOrphanWhites(
        cells: CellArray,
        minLen: Int,
        lUseful: Int,
        protectedCells: Set<Pair<Int, Int>>,
    ) {
        var changed = true
        var guard = cells.width * cells.height
        while (changed && guard-- > 0) {
            changed = false
            for (r in 0 until cells.height) {
                for (c in 0 until cells.width) {
                    if (cells.isBlack(r, c)) continue
                    if (hasWhiteNeighbour(cells, r, c)) continue
                    if (tryWhitenOneNeighbour(cells, r, c, minLen, lUseful)) {
                        changed = true
                        continue
                    }
                    if (tryWhitenTwoNeighbours(cells, r, c, minLen, lUseful)) {
                        changed = true
                        continue
                    }
                    // Last resort: blacken the orphan IFF the resulting
                    // black is functional. If not, leave the orphan in
                    // place; SlotRegistry will reject.
                    if ((r to c) in protectedCells) continue
                    cells.set(r, c, CellArray.BLACK)
                    if (!isFunctional(cells, r, c, minLen)) {
                        cells.set(r, c, CellArray.EMPTY)
                    } else {
                        changed = true
                    }
                }
            }
        }
    }

    private fun tryWhitenOneNeighbour(
        cells: CellArray,
        r: Int,
        c: Int,
        minLen: Int,
        lUseful: Int,
    ): Boolean {
        val candidates = listOf(r to (c - 1), r to (c + 1), (r - 1) to c, (r + 1) to c)
        for ((nr, nc) in candidates) {
            if (nr !in 0 until cells.height || nc !in 0 until cells.width) continue
            if (!cells.isBlack(nr, nc)) continue
            if (nr == 0 && nc == 0) continue
            if (tryWhitenSafely(cells, nr, nc, minLen, lUseful)) return true
        }
        return false
    }

    private fun tryWhitenTwoNeighbours(
        cells: CellArray,
        r: Int,
        c: Int,
        minLen: Int,
        lUseful: Int,
    ): Boolean {
        val candidates =
            listOf(r to (c - 1), r to (c + 1), (r - 1) to c, (r + 1) to c)
                .filter { (nr, nc) -> nr in 0 until cells.height && nc in 0 until cells.width }
                .filter { (nr, nc) -> cells.isBlack(nr, nc) && !(nr == 0 && nc == 0) }
        for (i in candidates.indices) {
            val (r1, c1) = candidates[i]
            if (!tryWhitenSafely(cells, r1, c1, minLen, lUseful)) continue
            for (j in candidates.indices) {
                if (j == i) continue
                val (r2, c2) = candidates[j]
                if (!cells.isBlack(r2, c2)) continue
                if (tryWhitenSafely(cells, r2, c2, minLen, lUseful)) return true
            }
            cells.set(r1, c1, CellArray.BLACK)
        }
        return false
    }

    /**
     * Pass 6: walk every BLACK and whiten any that became
     * non-functional. Repeats up to 3 times (cascading whitenings can
     * promote others to dead).
     */
    private fun removeDeadBlacks(
        cells: CellArray,
        minLen: Int,
        lUseful: Int,
    ) {
        repeat(3) {
            var removed = 0
            for (r in 0 until cells.height) {
                for (c in 0 until cells.width) {
                    if (!cells.isBlack(r, c)) continue
                    if (r == 0 && c == 0) continue // corner is structurally fine
                    if (isFunctional(cells, r, c, minLen)) continue
                    cells.set(r, c, CellArray.EMPTY)
                    val h = runLengthHorizontal(cells, r, c)
                    val v = runLengthVertical(cells, r, c)
                    if (h > lUseful || v > lUseful) {
                        cells.set(r, c, CellArray.BLACK) // give up; SlotRegistry will reject
                    } else {
                        removed++
                    }
                }
            }
            if (removed == 0) return
        }
    }

    private fun densitySprinkle(
        cells: CellArray,
        minLen: Int,
        blackRatio: Double,
        protectedCells: Set<Pair<Int, Int>>,
        random: Random,
    ) {
        val area = cells.width * cells.height
        val target = (blackRatio * area).toInt().coerceAtLeast(1)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (cells.isBlack(r, c)) continue
                if ((r to c) in protectedCells) continue
                candidates += r to c
            }
        }
        candidates.shuffle(random)
        var i = 0
        while (cells.countBlack() < target && i < candidates.size) {
            val (r, c) = candidates[i++]
            if (canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
            }
        }
    }

    /**
     * Bias-aware density sprinkle (spec §4.4 pass 5 scored variant). Prefers
     * placements that don't shorten any neighbouring run below [lMinGood]
     * and that don't create length-2 neighbours. Score: `(good, -cost)` where
     * `cost = shortfall + lengthTwoPenalty × len2Count` (spec §4.5.3).
     */
    private fun densitySprinkleScored(
        cells: CellArray,
        minLen: Int,
        blackRatio: Double,
        lMinGood: Int,
        lengthTwoPenalty: Double,
        protectedCells: Set<Pair<Int, Int>>,
        random: Random,
    ) {
        val area = cells.width * cells.height
        val target = (blackRatio * area).toInt().coerceAtLeast(1)

        data class Scored(
            val r: Int,
            val c: Int,
            val good: Int,
            val cost: Double,
        )
        val scored = mutableListOf<Scored>()
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (cells.isBlack(r, c)) continue
                if ((r to c) in protectedCells) continue
                if (!canPlaceBlack(cells, r, c, minLen)) continue
                val s = splitScore(cells, r, c, lMinGood)
                scored += Scored(r, c, s.good, s.shortfall + lengthTwoPenalty * s.len2Count)
            }
        }
        scored.shuffle(random)
        scored.sortWith(compareByDescending<Scored> { it.good }.thenBy { it.cost })
        var i = 0
        while (cells.countBlack() < target && i < scored.size) {
            val s = scored[i++]
            if (canPlaceBlack(cells, s.r, s.c, minLen)) {
                cells.set(s.r, s.c, CellArray.BLACK)
            }
        }
    }

    /**
     * Score a candidate placement at `(r, c)`:
     *  - `good`: neighbouring runs that would remain ≥ [lMinGood].
     *  - `shortfall`: total `max(0, lMinGood − run)` across neighbours.
     *  - `len2Count`: neighbouring runs that would be exactly length 2
     *    (spec §4.5.3).
     */
    private data class CellScore(
        val good: Int,
        val shortfall: Int,
        val len2Count: Int,
    )

    private fun splitScore(
        cells: CellArray,
        r: Int,
        c: Int,
        lMinGood: Int,
    ): CellScore {
        val prior = cells.get(r, c)
        cells.set(r, c, CellArray.BLACK)
        try {
            var good = 0
            var shortfall = 0
            var len2 = 0
            val neighbours =
                intArrayOf(
                    if (c - 1 >= 0 && !cells.isBlack(r, c - 1)) runLengthHorizontal(cells, r, c - 1) else -1,
                    if (c + 1 < cells.width && !cells.isBlack(r, c + 1)) runLengthHorizontal(cells, r, c + 1) else -1,
                    if (r - 1 >= 0 && !cells.isBlack(r - 1, c)) runLengthVertical(cells, r - 1, c) else -1,
                    if (r + 1 < cells.height && !cells.isBlack(r + 1, c)) runLengthVertical(cells, r + 1, c) else -1,
                )
            for (len in neighbours) {
                if (len < 0) continue
                if (len == 2) len2++
                if (len >= lMinGood) good++ else shortfall += (lMinGood - len)
            }
            return CellScore(good, shortfall, len2)
        } finally {
            cells.set(r, c, prior)
        }
    }

    private fun capLongHorizontalRuns(
        cells: CellArray,
        r: Int,
        minLen: Int,
        lCap: Int,
        protectedCells: Set<Pair<Int, Int>>,
        lengthTwoPenalty: Double,
        random: Random,
    ): Boolean {
        var changed = false
        var c = 0
        while (c < cells.width) {
            if (cells.isBlack(r, c)) {
                c++
                continue
            }
            val start = c
            while (c < cells.width && !cells.isBlack(r, c)) c++
            val length = c - start
            if (length > lCap) {
                val pos =
                    pickInsertColumn(
                        cells = cells,
                        r = r,
                        start = start,
                        length = length,
                        minLen = minLen,
                        lengthTwoPenalty = lengthTwoPenalty,
                        protectedCells = protectedCells,
                        random = random,
                    )
                if (pos != null) {
                    cells.set(r, pos, CellArray.BLACK)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun capLongVerticalRuns(
        cells: CellArray,
        c: Int,
        minLen: Int,
        lCap: Int,
        protectedCells: Set<Pair<Int, Int>>,
        lengthTwoPenalty: Double,
        random: Random,
    ): Boolean {
        var changed = false
        var r = 0
        while (r < cells.height) {
            if (cells.isBlack(r, c)) {
                r++
                continue
            }
            val start = r
            while (r < cells.height && !cells.isBlack(r, c)) r++
            val length = r - start
            if (length > lCap) {
                val pos =
                    pickInsertRow(
                        cells = cells,
                        c = c,
                        start = start,
                        length = length,
                        minLen = minLen,
                        lengthTwoPenalty = lengthTwoPenalty,
                        protectedCells = protectedCells,
                        random = random,
                    )
                if (pos != null) {
                    cells.set(pos, c, CellArray.BLACK)
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * Pick the column to split a long horizontal run by. When
     * [lengthTwoPenalty] is `0`, falls through to midpoint-first random
     * order (the original behaviour). Otherwise scores each feasible
     * candidate by `-(shortfall + lengthTwoPenalty × len2Count)` and
     * picks the highest-scoring one (spec §4.5.3).
     */
    private fun pickInsertColumn(
        cells: CellArray,
        r: Int,
        start: Int,
        length: Int,
        minLen: Int,
        lengthTwoPenalty: Double,
        protectedCells: Set<Pair<Int, Int>>,
        random: Random,
    ): Int? {
        if (lengthTwoPenalty <= 0.0) {
            return insertNearMidpoint(start, length, random) { p ->
                (r to p) !in protectedCells && canPlaceBlack(cells, r, p, minLen)
            }
        }
        val feasible =
            (start until start + length).filter { p ->
                (r to p) !in protectedCells && canPlaceBlack(cells, r, p, minLen)
            }
        if (feasible.isEmpty()) return null
        val scored =
            feasible.map { p ->
                val s = splitScore(cells, r, p, minLen)
                Triple(p, s.shortfall + lengthTwoPenalty * s.len2Count, random.nextInt())
            }
        return scored.minWith(compareBy({ it.second }, { it.third })).first
    }

    private fun pickInsertRow(
        cells: CellArray,
        c: Int,
        start: Int,
        length: Int,
        minLen: Int,
        lengthTwoPenalty: Double,
        protectedCells: Set<Pair<Int, Int>>,
        random: Random,
    ): Int? {
        if (lengthTwoPenalty <= 0.0) {
            return insertNearMidpoint(start, length, random) { p ->
                (p to c) !in protectedCells && canPlaceBlack(cells, p, c, minLen)
            }
        }
        val feasible =
            (start until start + length).filter { p ->
                (p to c) !in protectedCells && canPlaceBlack(cells, p, c, minLen)
            }
        if (feasible.isEmpty()) return null
        val scored =
            feasible.map { p ->
                val s = splitScore(cells, p, c, minLen)
                Triple(p, s.shortfall + lengthTwoPenalty * s.len2Count, random.nextInt())
            }
        return scored.minWith(compareBy({ it.second }, { it.third })).first
    }

    /**
     * Search positions in `[start, start + length)` in midpoint-first
     * order with random tie-breaking and return the first one for
     * which [predicate] returns true. Null if none qualify.
     */
    private inline fun insertNearMidpoint(
        start: Int,
        length: Int,
        random: Random,
        predicate: (Int) -> Boolean,
    ): Int? {
        val mid = start + length / 2
        val positions =
            (start until start + length)
                .sortedBy {
                    val d = it - mid
                    if (d == 0) -1 else d * d
                }.let {
                    if (it.size > 1) {
                        val first = it.first()
                        val rest = it.drop(1).toMutableList().also { l -> l.shuffle(random) }
                        listOf(first) + rest
                    } else {
                        it
                    }
                }
        for (pos in positions) if (predicate(pos)) return pos
        return null
    }

    private fun seedBoundarySkeleton(
        cells: CellArray,
        width: Int,
        height: Int,
    ) {
        cells.set(0, 0, CellArray.BLACK)
        var c = 2
        while (c <= width - 2) {
            cells.set(0, c, CellArray.BLACK)
            c += 2
        }
        if (width % 2 == 1) cells.set(0, width - 1, CellArray.BLACK)
        var r = 2
        while (r <= height - 2) {
            cells.set(r, 0, CellArray.BLACK)
            r += 2
        }
        if (height % 2 == 1) cells.set(height - 1, 0, CellArray.BLACK)
    }

    /**
     * Feature-aware boundary: exactly ONE feature anchor — either an H
     * anchor (row 0 from `(0, 1)` of length `hFeat`) or a V anchor
     * (column 0 from `(1, 0)` of length `vFeat`), never both. Caller
     * ensures exclusivity via [protectedCells].
     *
     * Single-direction is a deliberate constraint: with both features
     * active, four slots (H anchor, V anchor, row-1 H, col-1 V) would
     * converge on `(0, 0)` and exceed the 2-clue cap on `ClueCell`.
     * Single-direction restricts the corner to at most two clues.
     *
     * Forced blacks (H-only case shown; V-only is symmetric):
     *  - `(0, 0)` — corner / clue host for the H anchor (RIGHT).
     *  - `(0, hFeat + 1)` — H terminator (if in bounds).
     *  - `(1, c)` for `c in 1..hFeat` — required so each column `c`
     *    above `(1, c)` doesn't form an unclueable V slot at row 0,
     *    and so row 1 doesn't form an unclueable H slot at column 0.
     *    `removeDeadBlacks` cleans up any that end up dead (rare).
     */
    private fun seedBoundaryWithFeatures(
        cells: CellArray,
        width: Int,
        height: Int,
        protectedCells: Set<Pair<Int, Int>>,
        minLen: Int,
    ) {
        val hFeat = (1 until width).filter { (0 to it) in protectedCells }.maxOrNull() ?: 0
        val vFeat = (1 until height).filter { (it to 0) in protectedCells }.maxOrNull() ?: 0

        cells.set(0, 0, CellArray.BLACK)
        if (hFeat > 0) {
            if (hFeat + 1 < width) cells.set(0, hFeat + 1, CellArray.BLACK)
            if (height > 1) {
                for (c in 1..hFeat) {
                    if (c < width) cells.set(1, c, CellArray.BLACK)
                }
            }
            // Column 0 past the corner uses the regular every-other pattern.
            var r = 2
            while (r <= height - 2) {
                cells.set(r, 0, CellArray.BLACK)
                r += 2
            }
            if (height % 2 == 1) cells.set(height - 1, 0, CellArray.BLACK)
            // Row 0 past the terminator continues every-other.
            val rowStart = hFeat + 3
            var c = rowStart
            while (c <= width - 2) {
                cells.set(0, c, CellArray.BLACK)
                c += 2
            }
            if (width % 2 == 1 && width - 1 > hFeat + 1) cells.set(0, width - 1, CellArray.BLACK)
        } else if (vFeat > 0) {
            if (vFeat + 1 < height) cells.set(vFeat + 1, 0, CellArray.BLACK)
            if (width > 1) {
                for (r in 1..vFeat) {
                    if (r < height) cells.set(r, 1, CellArray.BLACK)
                }
            }
            var c = 2
            while (c <= width - 2) {
                cells.set(0, c, CellArray.BLACK)
                c += 2
            }
            if (width % 2 == 1) cells.set(0, width - 1, CellArray.BLACK)
            val colStart = vFeat + 3
            var r = colStart
            while (r <= height - 2) {
                cells.set(r, 0, CellArray.BLACK)
                r += 2
            }
            if (height % 2 == 1 && height - 1 > vFeat + 1) cells.set(height - 1, 0, CellArray.BLACK)
        }
    }

    // ---- Helpers ----

    private fun hasWhiteNeighbour(
        cells: CellArray,
        r: Int,
        c: Int,
    ): Boolean =
        (r > 0 && !cells.isBlack(r - 1, c)) ||
            (r < cells.height - 1 && !cells.isBlack(r + 1, c)) ||
            (c > 0 && !cells.isBlack(r, c - 1)) ||
            (c < cells.width - 1 && !cells.isBlack(r, c + 1))

    /** Full bidirectional run length through `(r, c)`. 0 if (r, c) is BLACK. */
    private fun runLengthHorizontal(
        cells: CellArray,
        r: Int,
        c: Int,
    ): Int {
        if (cells.isBlack(r, c)) return 0
        var left = c
        while (left > 0 && !cells.isBlack(r, left - 1)) left--
        var right = c
        while (right < cells.width - 1 && !cells.isBlack(r, right + 1)) right++
        return right - left + 1
    }

    private fun runLengthVertical(
        cells: CellArray,
        r: Int,
        c: Int,
    ): Int {
        if (cells.isBlack(r, c)) return 0
        var up = r
        while (up > 0 && !cells.isBlack(up - 1, c)) up--
        var down = r
        while (down < cells.height - 1 && !cells.isBlack(down + 1, c)) down++
        return down - up + 1
    }

    /** Forward (right-only) run length from (r, c), inclusive. 0 if (r, c) is BLACK. */
    private fun forwardRunHorizontal(
        cells: CellArray,
        r: Int,
        c: Int,
    ): Int {
        if (cells.isBlack(r, c)) return 0
        var end = c
        while (end < cells.width && !cells.isBlack(r, end)) end++
        return end - c
    }

    /** Forward (down-only) run length from (r, c), inclusive. 0 if (r, c) is BLACK. */
    private fun forwardRunVertical(
        cells: CellArray,
        r: Int,
        c: Int,
    ): Int {
        if (cells.isBlack(r, c)) return 0
        var end = r
        while (end < cells.height && !cells.isBlack(end, c)) end++
        return end - r
    }
}
