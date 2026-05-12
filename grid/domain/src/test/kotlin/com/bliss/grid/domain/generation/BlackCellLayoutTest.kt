package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
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
}
