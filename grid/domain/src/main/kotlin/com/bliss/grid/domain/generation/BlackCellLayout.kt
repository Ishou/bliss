package com.bliss.grid.domain.generation

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates and perturbs the BLACK-cell skeleton of a mots-fléchés
 * grid. The 5-pass procedure (spec §4) caps slot lengths at `L_target`,
 * eliminates orphan whites, and sprinkles to a target density.
 *
 * Boundary rule: cell `(0, 0)` is always [CellArray.BLACK]. Words cannot
 * start there because no clue cell can host both arrows simultaneously
 * one step "above" or "left" of `(0, 0)`. Every other position is fair
 * game subject to the min-word-length invariant.
 */
internal object BlackCellLayout {
    /**
     * Build a fresh [CellArray] populated with a black-cell layout that
     * satisfies the spec's structural invariants:
     *  - cells `(r, c) = (0, 0)` is BLACK;
     *  - no horizontal or vertical white run has length 1 (under `minLen >= 2`);
     *  - no run longer than `lUseful` survives;
     *  - approximately `blackRatio` of cells are BLACK.
     *
     * Deterministic given [random].
     */
    fun seed(
        width: Int,
        height: Int,
        minLen: Int,
        lTarget: Int,
        lUseful: Int,
        blackRatio: Double,
        random: Random,
    ): CellArray {
        require(minLen >= 2) { "minLen must be ≥ 2, was $minLen" }
        require(lTarget in minLen..lUseful) { "lTarget=$lTarget not in [minLen=$minLen, lUseful=$lUseful]" }
        require(blackRatio in 0.0..0.5) { "blackRatio $blackRatio out of [0.0, 0.5]" }

        val cells = CellArray(width, height)

        // Boundary skeleton: row 0 and column 0 alternate BLACK/LETTER so
        // every interior horizontal slot starting at column 0 (or vertical
        // slot starting at row 0) has a valid clue cell available at the
        // edge. Without this, layouts would routinely fail to admit valid
        // mots-fléchés clue positions for boundary slots.
        seedBoundarySkeleton(cells, width, height)

        // Pass 1+2: cap horizontal and vertical runs at lTarget by
        // midpoint-splitting any run longer than lTarget. Repeat until
        // stable — splits can produce new long runs perpendicularly.
        // The midpoint search order is jittered per seed so two
        // different seeds produce structurally different layouts.
        repeat(8) {
            var changed = false
            for (r in 0 until height) {
                if (capLongHorizontalRuns(cells, r, minLen, lTarget, random)) changed = true
            }
            for (c in 0 until width) {
                if (capLongVerticalRuns(cells, c, minLen, lTarget, random)) changed = true
            }
            if (!changed) return@repeat
        }

        // Pass 3: final guard for any run > lUseful that survived (e.g.
        // because canPlaceBlack rejected the midpoint).
        repeat(3) {
            var changed = false
            for (r in 0 until height) {
                if (capLongHorizontalRuns(cells, r, minLen, lUseful, random)) changed = true
            }
            for (c in 0 until width) {
                if (capLongVerticalRuns(cells, c, minLen, lUseful, random)) changed = true
            }
            if (!changed) return@repeat
        }

        // Pass 4: eliminate orphan whites (white cells with no white neighbour).
        eliminateOrphans(cells)

        // Pass 5: density sprinkle to blackRatio.
        densitySprinkle(cells, minLen, blackRatio, random)

        // Pass 4 again — sprinkling may have created new orphans.
        eliminateOrphans(cells)

        return cells
    }

    /**
     * Perturb a layout by targeting "hot" slots and a small random
     * fraction of cells. [hotCells] are positions of cells inside the
     * smallest-domain slots' middles, sorted longest-slot-first.
     */
    fun perturb(
        cells: CellArray,
        minLen: Int,
        lUseful: Int,
        hotCells: List<Pair<Int, Int>>,
        intensity: Double,
        random: Random,
    ) {
        val area = cells.width * cells.height
        var moves = max(2, (intensity * area).toInt())

        // Try the up-to-2 hottest slot middles first.
        for ((r, c) in hotCells.take(2)) {
            if (moves <= 0) break
            if (!cells.isBlack(r, c) && canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
                moves--
            }
        }

        // Random moves: toggle a few cells. Whitening BLACK to EMPTY is
        // riskier (may create runs > lUseful), so it's done with lower
        // probability and rejected when it would over-extend a run.
        var attempts = 0
        while (moves > 0 && attempts < area * 4) {
            attempts++
            val r = random.nextInt(cells.height)
            val c = random.nextInt(cells.width)
            if (r == 0 && c == 0) continue // corner is always BLACK
            if (cells.isBlack(r, c)) {
                if (random.nextDouble() < 0.4 && canWhiten(cells, r, c, lUseful)) {
                    cells.set(r, c, CellArray.EMPTY)
                    moves--
                }
            } else if (canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
                moves--
            }
        }

        eliminateOrphans(cells)
    }

    /**
     * Test whether turning `(r, c)` BLACK preserves the interlocking
     * invariant for every neighbouring white cell:
     *  - **Clue-layer cells** (row 0 OR col 0): at least one axis ≥
     *    [minLen]. They participate in only one slot direction.
     *  - **Interior cells** (row > 0 AND col > 0): BOTH axes ≥
     *    [minLen]. Every interior letter must be crossed by both a
     *    horizontal and a vertical slot.
     *
     * Pre-condition: the cell is currently EMPTY or a letter.
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
        var ok = true
        val neighbours = listOf(r to (c - 1), r to (c + 1), (r - 1) to c, (r + 1) to c)
        for ((nr, nc) in neighbours) {
            if (nr !in 0 until cells.height || nc !in 0 until cells.width) continue
            if (cells.isBlack(nr, nc)) continue
            val hRun = horizontalRunLength(cells, nr, nc)
            val vRun = verticalRunLength(cells, nr, nc)
            val isEdge = nr == 0 || nc == 0
            // Edge cells: a neighbour that would be "stranded" (both runs <
            // minLen) is acceptable — eliminateOrphans will turn it BLACK
            // (it becomes a harmless extra clue cell). This is essential
            // for capping very long boundary runs on large grids where
            // every midpoint candidate strands an alternating row-0 cell.
            // Interior cells: must keep at least one axis ≥ minLen so they
            // participate in some slot; both <minLen would create an
            // uncrossed interior letter, which the grid validator rejects.
            val valid = if (isEdge) true else hRun >= minLen || vRun >= minLen
            if (!valid) {
                ok = false; break
            }
            // Additional invariant: interior cells must have *both* axes ≥
            // minLen because GridValidator requires interior letters to be
            // doubly crossed.
            if (!isEdge && (hRun < minLen || vRun < minLen)) {
                ok = false; break
            }
        }
        cells.set(r, c, prior)
        return ok
    }

    /** Test whether whitening `(r, c)` keeps every neighbour run ≤ [lUseful]. */
    private fun canWhiten(
        cells: CellArray,
        r: Int,
        c: Int,
        lUseful: Int,
    ): Boolean {
        if (r == 0 && c == 0) return false
        if (!cells.isBlack(r, c)) return false
        cells.set(r, c, CellArray.EMPTY)
        // Combined runs through (r, c).
        val hRun = horizontalRunLength(cells, r, c)
        val vRun = verticalRunLength(cells, r, c)
        cells.set(r, c, CellArray.BLACK)
        return hRun <= lUseful && vRun <= lUseful
    }

    /** Length of the maximal white run containing `(r, c)` in row `r`. 0 if `(r, c)` is BLACK. */
    private fun horizontalRunLength(
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

    /** Length of the maximal white run containing `(r, c)` in column `c`. */
    private fun verticalRunLength(
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

    /**
     * Place BLACK at row-0 even columns (0, 2, 4, …) and column-0 even
     * rows (0, 2, 4, …). For odd width, add BLACK at (0, w-1); for odd
     * height, BLACK at (h-1, 0). This matches the mots-fléchés
     * convention seen in printed grids: row 0 / col 0 alternate
     * BLACK (clue) / LETTER (first letter of a perpendicular slot).
     */
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
     * Find each maximal horizontal white run in row [r] of length >
     * [lCap] and insert BLACK near its midpoint, trying ±1 offsets if
     * the strict midpoint fails [canPlaceBlack]. Returns true on any
     * insertion.
     */
    private fun capLongHorizontalRuns(
        cells: CellArray,
        r: Int,
        minLen: Int,
        lCap: Int,
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
                val mid = start + length / 2
                // Try every position in the run, preferring midpoint-close.
                // Some seeds need an off-midpoint cut on the bottom/right edge
                // because interior orphans block the strict midpoint.
                val positions = (start until start + length).sortedBy {
                    val d = it - mid
                    // Negative to prefer slightly-left ties, with a per-call
                    // random salt so deterministic seeds still produce
                    // structurally different layouts.
                    if (d == 0) -1 else d * d
                }.let {
                    if (it.size > 1) {
                        val first = it.first()
                        val rest = it.drop(1).toMutableList().also { l -> l.shuffle(random) }
                        listOf(first) + rest
                    } else it
                }
                for (pos in positions) {
                    if (pos !in start until start + length) continue
                    if (canPlaceBlack(cells, r, pos, minLen)) {
                        cells.set(r, pos, CellArray.BLACK)
                        changed = true
                        break
                    }
                }
            }
        }
        return changed
    }

    /** Vertical twin of [capLongHorizontalRuns]. */
    private fun capLongVerticalRuns(
        cells: CellArray,
        c: Int,
        minLen: Int,
        lCap: Int,
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
                val mid = start + length / 2
                // Try every position in the run, preferring midpoint-close.
                // Some seeds need an off-midpoint cut on the bottom/right edge
                // because interior orphans block the strict midpoint.
                val positions = (start until start + length).sortedBy {
                    val d = it - mid
                    // Negative to prefer slightly-left ties, with a per-call
                    // random salt so deterministic seeds still produce
                    // structurally different layouts.
                    if (d == 0) -1 else d * d
                }.let {
                    if (it.size > 1) {
                        val first = it.first()
                        val rest = it.drop(1).toMutableList().also { l -> l.shuffle(random) }
                        listOf(first) + rest
                    } else it
                }
                for (pos in positions) {
                    if (pos !in start until start + length) continue
                    if (canPlaceBlack(cells, pos, c, minLen)) {
                        cells.set(pos, c, CellArray.BLACK)
                        changed = true
                        break
                    }
                }
            }
        }
        return changed
    }


    /**
     * Turn every white cell with no white neighbour into BLACK. A
     * white cell isolated by blacks can never be part of a slot.
     */
    private fun eliminateOrphans(cells: CellArray) {
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (!cells.isEmpty(r, c) && !cells.isLetter(r, c)) continue
                val hasWhiteNeighbour =
                    (r > 0 && !cells.isBlack(r - 1, c)) ||
                        (r < cells.height - 1 && !cells.isBlack(r + 1, c)) ||
                        (c > 0 && !cells.isBlack(r, c - 1)) ||
                        (c < cells.width - 1 && !cells.isBlack(r, c + 1))
                if (!hasWhiteNeighbour) {
                    cells.set(r, c, CellArray.BLACK)
                }
            }
        }
    }

    private fun densitySprinkle(
        cells: CellArray,
        minLen: Int,
        blackRatio: Double,
        random: Random,
    ) {
        val area = cells.width * cells.height
        val target = (blackRatio * area).toInt().coerceAtLeast(1)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (!cells.isBlack(r, c)) candidates += r to c
            }
        }
        candidates.shuffle(random)
        var i = 0
        while (cells.countBlack() < target && i < candidates.size) {
            val (r, c) = candidates[i]
            i++
            if (canPlaceBlack(cells, r, c, minLen)) {
                cells.set(r, c, CellArray.BLACK)
            }
        }
    }
}
