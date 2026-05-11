package com.bliss.grid.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BenchDiffTest {
    private val header =
        "seed,total_ms,skeleton_ms,slot_plan_ms,fill_ms," +
            "slot_plan_backtracks,fill_backtracks,fill_repo_calls," +
            "fill_first_slot_domain,shape_hash,succeeded"

    private fun writeCsv(
        dir: Path,
        name: String,
        rows: List<String>,
    ): Path {
        val p = dir.resolve(name)
        Files.writeString(p, (listOf(header) + rows).joinToString("\n") + "\n")
        return p
    }

    private fun row(
        seed: Long,
        totalMs: Long,
        skeletonMs: Long = 1,
        slotPlanMs: Long = 5,
        fillMs: Long = totalMs - 6,
        shapeHash: String = "h$seed",
        succeeded: Boolean = true,
    ): String = "$seed,$totalMs,$skeletonMs,$slotPlanMs,$fillMs,0,0,0,-1,$shapeHash,$succeeded"

    @Test
    fun `identical CSVs produce zero deltas across every metric`(
        @TempDir dir: Path,
    ) {
        val rows = (1L..5L).map { row(it, totalMs = 100L * it) }
        val a = writeCsv(dir, "a.csv", rows)
        val b = writeCsv(dir, "b.csv", rows)
        val table = BenchDiff.compare(a, b)
        assertThat(table).contains("+0%")
        assertThat(table).doesNotContain("▲")
        assertThat(table).doesNotContain("▼")
    }

    @Test
    fun `2x regression in total_ms p50 flags upward`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 100L) }
        val current = (1L..10L).map { row(it, totalMs = 200L) }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("total_ms p50")
        assertThat(table).contains("+100%")
        assertThat(table).contains("▲")
    }

    @Test
    fun `2x improvement in total_ms p50 flags downward`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 200L) }
        val current = (1L..10L).map { row(it, totalMs = 100L) }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("-50%")
        assertThat(table).contains("▼")
    }

    @Test
    fun `shape_collision_rate is reported`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 100L, shapeHash = "u$it") }
        val current = (1L..10L).map { row(it, totalMs = 100L, shapeHash = if (it <= 5) "c$it" else "c${it - 5}") }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("shape_collision_rate")
    }

    @Test
    fun `success_rate is reported`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 100L, succeeded = true) }
        val current = (1L..10L).map { row(it, totalMs = 100L, succeeded = it <= 8) }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("success_rate")
    }
}
