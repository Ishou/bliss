package com.bliss.grid.api

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads two bench CSVs (baseline + current) and produces a side-by-side
 * comparison table as a multi-line string. Flag column: `▼` for an
 * improvement of 10%+, `▲` for a regression of 10%+, blank otherwise.
 *
 * "Improvement" means "smaller is better" for latency / collision-rate
 * metrics and "larger is better" for success_rate. The flag direction is
 * inverted accordingly.
 *
 * Tolerant of missing columns: a metric absent from either side renders
 * as `n/a` and is not flagged.
 */
object BenchDiff {
    private const val FLAG_THRESHOLD = 0.10
    private const val COL_LABEL = 24
    private const val COL_NUM = 12
    private const val COL_DELTA = 9
    private const val COL_FLAG = 4

    fun compare(
        baseline: Path,
        current: Path,
    ): String {
        val a = readCsv(baseline)
        val b = readCsv(current)
        val sb = StringBuilder()
        sb.append(header()).append('\n')

        addPercentileRow(sb, "total_ms p50", a, b, "total_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "total_ms p95", a, b, "total_ms", 95, smallerIsBetter = true)
        addPercentileRow(sb, "total_ms p99", a, b, "total_ms", 99, smallerIsBetter = true)
        addPercentileRow(sb, "skeleton_ms p50", a, b, "skeleton_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "slot_plan_ms p50", a, b, "slot_plan_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "fill_ms p50", a, b, "fill_ms", 50, smallerIsBetter = true)

        addRateRow(sb, "shape_collision_rate", collisionRate(a), collisionRate(b), smallerIsBetter = true)
        addRateRow(sb, "success_rate", successRate(a), successRate(b), smallerIsBetter = false)

        return sb.toString()
    }

    private data class Csv(
        val header: List<String>,
        val rows: List<List<String>>,
    )

    private fun readCsv(path: Path): Csv {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "CSV is empty: $path" }
        val header = lines.first().split(",")
        val rows = lines.drop(1).map { it.split(",") }
        return Csv(header, rows)
    }

    private fun Csv.columnIndex(name: String): Int? {
        val i = header.indexOf(name)
        return if (i >= 0) i else null
    }

    private fun Csv.longsAt(column: String): List<Long>? {
        val i = columnIndex(column) ?: return null
        return rows.mapNotNull { it.getOrNull(i)?.toLongOrNull() }
    }

    private fun percentile(
        sorted: List<Long>,
        pct: Int,
    ): Long = sorted[(pct * (sorted.size - 1)) / 100]

    private fun collisionRate(csv: Csv): Double {
        val i = csv.columnIndex("shape_hash") ?: return Double.NaN
        val hashes = csv.rows.mapNotNull { it.getOrNull(i) }.filter { it.isNotBlank() }
        if (hashes.isEmpty()) return Double.NaN
        val unique = hashes.distinct().size
        return (hashes.size - unique).toDouble() / hashes.size
    }

    private fun successRate(csv: Csv): Double {
        val i = csv.columnIndex("succeeded") ?: return Double.NaN
        val flags = csv.rows.mapNotNull { it.getOrNull(i)?.toBooleanStrictOrNull() }
        if (flags.isEmpty()) return Double.NaN
        return flags.count { it }.toDouble() / flags.size
    }

    private fun addPercentileRow(
        sb: StringBuilder,
        label: String,
        a: Csv,
        b: Csv,
        column: String,
        pct: Int,
        smallerIsBetter: Boolean,
    ) {
        val aVals = a.longsAt(column)?.sorted()
        val bVals = b.longsAt(column)?.sorted()
        val baseVal = aVals?.takeIf { it.isNotEmpty() }?.let { percentile(it, pct) }
        val curVal = bVals?.takeIf { it.isNotEmpty() }?.let { percentile(it, pct) }
        appendRow(
            sb,
            label,
            baseVal?.toString(),
            curVal?.toString(),
            deltaPct(baseVal?.toDouble(), curVal?.toDouble()),
            smallerIsBetter,
        )
    }

    private fun addRateRow(
        sb: StringBuilder,
        label: String,
        aRate: Double,
        bRate: Double,
        smallerIsBetter: Boolean,
    ) {
        val base = if (aRate.isNaN()) null else "%.3f".format(aRate)
        val cur = if (bRate.isNaN()) null else "%.3f".format(bRate)
        appendRow(
            sb,
            label,
            base,
            cur,
            deltaPct(if (aRate.isNaN()) null else aRate, if (bRate.isNaN()) null else bRate),
            smallerIsBetter,
        )
    }

    private fun deltaPct(
        base: Double?,
        cur: Double?,
    ): Double? {
        if (base == null || cur == null) return null
        if (base == 0.0) return if (cur == 0.0) 0.0 else Double.POSITIVE_INFINITY
        return (cur - base) / base
    }

    private fun appendRow(
        sb: StringBuilder,
        label: String,
        base: String?,
        cur: String?,
        delta: Double?,
        smallerIsBetter: Boolean,
    ) {
        sb.append(label.padEnd(COL_LABEL))
        sb.append((base ?: "n/a").padEnd(COL_NUM))
        sb.append((cur ?: "n/a").padEnd(COL_NUM))
        val deltaStr = if (delta == null) "n/a" else "%+.0f%%".format(delta * 100)
        sb.append(deltaStr.padEnd(COL_DELTA))
        sb.append(flag(delta, smallerIsBetter).padEnd(COL_FLAG))
        sb.append('\n')
    }

    private fun flag(
        delta: Double?,
        smallerIsBetter: Boolean,
    ): String {
        if (delta == null || delta.isNaN() || !delta.isFinite()) return ""
        if (kotlin.math.abs(delta) < FLAG_THRESHOLD) return ""
        val regressed = if (smallerIsBetter) delta > 0 else delta < 0
        return if (regressed) "▲" else "▼"
    }

    private fun header(): String =
        "metric".padEnd(COL_LABEL) +
            "baseline".padEnd(COL_NUM) +
            "current".padEnd(COL_NUM) +
            "delta".padEnd(COL_DELTA) +
            "flag".padEnd(COL_FLAG)
}
