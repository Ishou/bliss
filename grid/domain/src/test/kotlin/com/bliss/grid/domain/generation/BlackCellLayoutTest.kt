package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class BlackCellLayoutTest {
    private fun maxHorizontalRun(cells: CellArray): Int {
        var maxRun = 0
        for (r in 0 until cells.height) {
            var run = 0
            for (c in 0 until cells.width) {
                if (cells.isBlack(r, c)) {
                    if (run > maxRun) maxRun = run
                    run = 0
                } else {
                    run++
                }
            }
            if (run > maxRun) maxRun = run
        }
        return maxRun
    }

    private fun maxVerticalRun(cells: CellArray): Int {
        var maxRun = 0
        for (c in 0 until cells.width) {
            var run = 0
            for (r in 0 until cells.height) {
                if (cells.isBlack(r, c)) {
                    if (run > maxRun) maxRun = run
                    run = 0
                } else {
                    run++
                }
            }
            if (run > maxRun) maxRun = run
        }
        return maxRun
    }

    /**
     * For every white cell, returns the larger of (horizontal-run-length,
     * vertical-run-length). A well-formed layout has every white cell
     * participating in at least one slot, so this value is ≥ minLen for
     * all white cells. Length-1 runs are tolerated if perpendicular ≥ minLen
     * (clue-layer cells in row 0 / col 0 work this way).
     */
    private fun minSlotRun(cells: CellArray): Int {
        var minRun = Int.MAX_VALUE
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (cells.isBlack(r, c)) continue
                val h = run(cells, r, c, dr = 0, dc = 1)
                val v = run(cells, r, c, dr = 1, dc = 0)
                val best = maxOf(h, v)
                if (best < minRun) minRun = best
            }
        }
        return if (minRun == Int.MAX_VALUE) 0 else minRun
    }

    private fun run(
        cells: CellArray,
        r: Int,
        c: Int,
        dr: Int,
        dc: Int,
    ): Int {
        var lo = 0
        var hi = 0
        var rr = r - dr
        var cc = c - dc
        while (rr in 0 until cells.height && cc in 0 until cells.width && !cells.isBlack(rr, cc)) {
            lo++
            rr -= dr
            cc -= dc
        }
        rr = r + dr
        cc = c + dc
        while (rr in 0 until cells.height && cc in 0 until cells.width && !cells.isBlack(rr, cc)) {
            hi++
            rr += dr
            cc += dc
        }
        return lo + 1 + hi
    }

    @Test
    fun `seed produces (0,0) as BLACK`() {
        val cells =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(0),
            )
        assertThat(cells.isBlack(0, 0)).isTrue()
    }

    @Test
    fun `no run shorter than minLen and no run longer than lUseful`() {
        val cells =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(123),
            )
        assertThat(minSlotRun(cells)).isGreaterThan(1) // ≥ minLen=2
        assertThat(maxHorizontalRun(cells)).isLessThanOrEqualTo(12)
        assertThat(maxVerticalRun(cells)).isLessThanOrEqualTo(12)
    }

    @Test
    fun `density approximates the target ratio within tolerance`() {
        val cells =
            BlackCellLayout.seed(
                width = 12,
                height = 12,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(7),
            )
        val total = 12 * 12
        val blacks = cells.countBlack()
        val ratio = blacks.toDouble() / total
        // Allow ±10pp tolerance; seed/pass interactions may push slightly above target.
        assertThat(abs(ratio - 0.18) < 0.10).isTrue()
    }

    @Test
    fun `seeding is deterministic given the same random seed`() {
        val a =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(42),
            )
        val b =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(42),
            )
        for (r in 0 until 10) {
            for (c in 0 until 10) {
                assertThat(a.get(r, c)).isEqualTo(b.get(r, c))
            }
        }
    }

    @Test
    fun `seeding with different seeds produces different layouts`() {
        val a =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(1),
            )
        val b =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(2),
            )
        var diff = 0
        for (r in 0 until 10) {
            for (c in 0 until 10) {
                if (a.get(r, c) != b.get(r, c)) diff++
            }
        }
        assertThat(diff).isGreaterThan(0) // at least one cell differs
    }

    @Test
    fun `perturb keeps min run length valid`() {
        val cells =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(99),
            )
        // No hot cells given; just random toggles.
        BlackCellLayout.perturb(
            cells = cells,
            minLen = 2,
            lUseful = 12,
            hotCells = emptyList(),
            intensity = 0.06,
            random = Random(99),
        )
        assertThat(cells.isBlack(0, 0)).isTrue() // (0, 0) remains BLACK
        assertThat(minSlotRun(cells)).isGreaterThan(1)
    }

    @Test
    fun `isFunctional returns false when no arrow targets a qualifying run`() {
        // 4×3: (1,1) BLACK. RIGHT target (1,2) has forward-run=1<2 (blocked by (1,3)=B).
        // DOWN target (2,1) has forward-run=1<2 (last row). c≠0 and r≠0 so
        // DOWN_RIGHT/RIGHT_DOWN do not apply.
        val cells = CellArray(4, 3)
        cells.set(1, 1, CellArray.BLACK)
        cells.set(1, 3, CellArray.BLACK)
        assertThat(BlackCellLayout.isFunctional(cells, 1, 1, 2)).isFalse()
    }

    @Test
    fun `isFunctional returns true via RIGHT arrow`() {
        // (1,0) BLACK — cells (1,1)(1,2)(1,3) form a 3-cell forward run. RIGHT qualifies.
        val cells = CellArray(4, 3)
        cells.set(1, 0, CellArray.BLACK)
        assertThat(BlackCellLayout.isFunctional(cells, 1, 0, 2)).isTrue()
    }

    @Test
    fun `isFunctional returns true via DOWN arrow`() {
        // (1,1) BLACK with (1,2) BLACK blocking RIGHT.
        // DOWN: (2,1)(3,1) form a 2-cell forward vertical run. DOWN qualifies.
        val cells = CellArray(4, 4)
        cells.set(1, 1, CellArray.BLACK)
        cells.set(1, 2, CellArray.BLACK)
        assertThat(BlackCellLayout.isFunctional(cells, 1, 1, 2)).isTrue()
    }

    @Test
    fun `isFunctional returns true via DOWN_RIGHT arrow when c is zero`() {
        // (1,0) BLACK, (1,1) BLACK blocks RIGHT; height=3 so DOWN forward-run=1<2.
        // DOWN_RIGHT (c==0): row-2 starts at (2,0) with forward-run=4≥2. DOWN_RIGHT qualifies.
        val cells = CellArray(4, 3)
        cells.set(1, 0, CellArray.BLACK)
        cells.set(1, 1, CellArray.BLACK)
        assertThat(BlackCellLayout.isFunctional(cells, 1, 0, 2)).isTrue()
    }

    @Test
    fun `isFunctional returns true via RIGHT_DOWN arrow when r is zero`() {
        // (0,2) BLACK, (1,2) BLACK blocks DOWN; width=4 so RIGHT forward-run at (0,3)=1<2.
        // RIGHT_DOWN (r==0): col-3 starts at (0,3) with forward-run=3≥2. RIGHT_DOWN qualifies.
        val cells = CellArray(4, 3)
        cells.set(0, 2, CellArray.BLACK)
        cells.set(1, 2, CellArray.BLACK)
        assertThat(BlackCellLayout.isFunctional(cells, 0, 2, 2)).isTrue()
    }

    @Test
    fun `canPlaceBlack rejects placement that would make the new black dead`() {
        // Bottom-right corner of 4×4: no RIGHT (boundary), no DOWN (boundary),
        // c=3≠0 so no DOWN_RIGHT, r=3≠0 so no RIGHT_DOWN. Check 1 passes
        // (left-run=3, up-run=3); Check 2 fails (no qualifying arrow).
        val cells = CellArray(4, 4)
        assertThat(BlackCellLayout.canPlaceBlack(cells, 3, 3, 2)).isFalse()
    }

    @Test
    fun `canPlaceBlack rejects placement that orphans an existing neighbour black`() {
        // (0,0)=B neighbour. (1,0)=B blocks its DOWN and DOWN_RIGHT arrows.
        // Placing at (0,1) would block (0,0)'s RIGHT and RIGHT_DOWN arrows too,
        // leaving (0,0) with no qualifying arrow — Check 3 fires.
        val cells = CellArray(4, 3)
        cells.set(0, 0, CellArray.BLACK)
        cells.set(1, 0, CellArray.BLACK)
        assertThat(BlackCellLayout.canPlaceBlack(cells, 0, 1, 2)).isFalse()
    }

    @Test
    fun `seed produces no dead black cells`() {
        // With lUseful=12 on a 10×10 grid, removeDeadBlacks never hits the
        // over-long-run bail-out (max run ≤ 10 < 12), so the invariant is hard.
        val cells =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(0),
            )
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (!cells.isBlack(r, c)) continue
                if (r == 0 && c == 0) continue
                assertThat(BlackCellLayout.isFunctional(cells, r, c, 2)).isTrue()
            }
        }
    }

    @Test
    fun `perturb preserves the no-dead-black invariant`() {
        val cells =
            BlackCellLayout.seed(
                width = 10,
                height = 10,
                minLen = 2,
                lTarget = 9,
                lUseful = 12,
                blackRatio = 0.18,
                random = Random(5),
            )
        BlackCellLayout.perturb(
            cells = cells,
            minLen = 2,
            lUseful = 12,
            hotCells = emptyList(),
            intensity = 0.06,
            random = Random(5),
        )
        for (r in 0 until cells.height) {
            for (c in 0 until cells.width) {
                if (!cells.isBlack(r, c)) continue
                if (r == 0 && c == 0) continue
                assertThat(BlackCellLayout.isFunctional(cells, r, c, 2)).isTrue()
            }
        }
    }
}
