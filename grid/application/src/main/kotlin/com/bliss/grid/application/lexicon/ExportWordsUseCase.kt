package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.ExportSelectionCriteria
import com.bliss.grid.domain.lexicon.WordCorpusExportSink
import com.bliss.grid.domain.lexicon.WordCorpusReader

/**
 * `export-words` use case (ADR-0013 §7, §8): stream selected rows from the
 * corpus reader straight into the export sink. The reader's `withExportRows`
 * scope keeps the underlying resource (JDBC connection) open for the duration
 * of writing.
 */
class ExportWordsUseCase(
    private val reader: WordCorpusReader,
    private val sink: WordCorpusExportSink,
) {
    fun execute(criteria: ExportSelectionCriteria): ExportWordsReport {
        val written = reader.withExportRows(criteria) { rows -> sink.write(rows) }
        return ExportWordsReport(rowsWritten = written)
    }
}

data class ExportWordsReport(val rowsWritten: Int)
