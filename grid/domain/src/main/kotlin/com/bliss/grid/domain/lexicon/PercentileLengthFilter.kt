// Per-length percentile filter for the export pipeline (ADR-0013 §2 §4 follow-up).
// Pure-domain, no IO. Drops the bottom (1 - ratio) fraction of each length bucket
// by frequency, keeping curated-source rows unconditionally and letting them
// override grammalecte rows with the same (language, word) key.
package com.bliss.grid.domain.lexicon

/**
 * Per-length keep-ratio configuration. A ratio of 0.5 keeps the top half by
 * frequency; 0.0 drops the entire bucket; 1.0 keeps everything.
 *
 * Lengths absent from [keepRatioByLength] use [defaultKeepRatio].
 */
data class PercentileLengthFilterConfig(
    val keepRatioByLength: Map<Int, Double>,
    val defaultKeepRatio: Double,
) {
    init {
        require(defaultKeepRatio in 0.0..1.0) { "defaultKeepRatio must be in [0,1], got $defaultKeepRatio" }
        keepRatioByLength.forEach { (len, ratio) ->
            require(ratio in 0.0..1.0) { "keepRatioByLength[$len] must be in [0,1], got $ratio" }
        }
    }
}

object PercentileLengthFilter {
    /**
     * Returns rows that pass the per-length percentile filter.
     *
     * Curated rows (those with `source == [curatedSource]`) bypass the filter
     * and override any non-curated row with the same `(language, word)` key.
     */
    fun apply(
        rows: List<ExportRow>,
        config: PercentileLengthFilterConfig,
        curatedSource: String,
    ): List<ExportRow> {
        val curated = rows.filter { it.source == curatedSource }
        val curatedKeys = curated.mapTo(HashSet()) { it.language to it.word }
        val regular = rows.filter { it.source != curatedSource && (it.language to it.word) !in curatedKeys }

        val survivors =
            regular.groupBy { it.length }.flatMap { (length, bucket) ->
                val ratio = config.keepRatioByLength[length] ?: config.defaultKeepRatio
                keepTopFraction(bucket, ratio)
            }
        return curated + survivors
    }

    private fun keepTopFraction(
        bucket: List<ExportRow>,
        ratio: Double,
    ): List<ExportRow> {
        if (ratio <= 0.0) return emptyList()
        if (ratio >= 1.0) return bucket
        val dropCount = (bucket.size * (1.0 - ratio)).toInt()
        val keepCount = bucket.size - dropCount
        return bucket.sortedByDescending { it.frequency ?: Long.MIN_VALUE }.take(keepCount)
    }
}
