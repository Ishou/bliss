package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.CURATED_SOURCE
import com.bliss.grid.domain.lexicon.CuratedSourceReader
import com.bliss.grid.domain.lexicon.ExportRow
import com.bliss.grid.domain.lexicon.ExportSelectionCriteria
import com.bliss.grid.domain.lexicon.PercentileLengthFilter
import com.bliss.grid.domain.lexicon.PercentileLengthFilterConfig
import com.bliss.grid.domain.lexicon.WordCorpusExportSink
import com.bliss.grid.domain.lexicon.WordCorpusReader

/**
 * `export-words` use case (ADR-0013 §7, §8): stream rows from the corpus
 * reader, merge in hand-curated rows from [curatedReader] (which bypass the
 * frequency filter and override grammalecte rows on `(language, word)`
 * collisions), apply the per-length percentile filter, then write the result
 * to the sink in `(language, word)` order so the output stays byte-stable
 * across runs.
 *
 * The reader's `withExportRows` scope keeps the underlying resource (JDBC
 * connection) open for the duration of writing.
 */
class ExportWordsUseCase(
    private val reader: WordCorpusReader,
    private val sink: WordCorpusExportSink,
    private val curatedReader: CuratedSourceReader,
    private val percentileConfig: PercentileLengthFilterConfig,
) {
    fun execute(criteria: ExportSelectionCriteria): ExportWordsReport {
        val written =
            reader.withExportRows(criteria) { dbRows ->
                val relicensed = dbRows.map(::reLicense).toList()
                val merged = relicensed + curatedReader.rows(criteria.language).toList()
                val filtered = PercentileLengthFilter.apply(merged, percentileConfig, CURATED_SOURCE)
                val sorted = filtered.sortedWith(compareBy({ it.language }, { it.word }))
                sink.write(sorted.asSequence())
            }
        return ExportWordsReport(rowsWritten = written)
    }

    /**
     * When the reader picked a clue_candidates row, the row's `source` carries
     * the candidate's source label (e.g. `dbnary-synonym`). The original
     * `words.source_license` (typically MPL-2.0 from grammalecte) no longer
     * applies — map the license to match the new provenance. ADR-0024
     * §"Attribution obligation" requires this trail to be preserved on export.
     *
     * Sources not listed keep the row's existing license (covers grammalecte /
     * hunspell / model-generated clues whose license matches the row's
     * underlying source already).
     */
    private fun reLicense(row: ExportRow): ExportRow {
        val mapped = LICENSE_BY_SOURCE[row.source] ?: return row
        return if (row.sourceLicense == mapped) row else row.copy(sourceLicense = mapped)
    }

    companion object {
        private val LICENSE_BY_SOURCE: Map<String, String> =
            mapOf(
                "curated" to "CC0-1.0",
                "dbnary-synonym" to "CC-BY-SA-4.0",
            )
    }
}

data class ExportWordsReport(
    val rowsWritten: Int,
)
