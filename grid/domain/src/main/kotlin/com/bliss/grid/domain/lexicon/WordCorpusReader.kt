package com.bliss.grid.domain.lexicon

/** A row emitted by `export-words` (ADR-0013 §7, §8). */
data class ExportRow(
    val word: String,
    val language: String,
    val length: Int,
    val frequency: Long?,
    val difficulty: Float?,
    val clue: String,
    val source: String,
    val sourceLicense: String,
)

/** Selection knobs for the export query. */
data class ExportSelectionCriteria(
    val language: String,
    /** When `true`, drop the `clue IS NOT NULL` filter. */
    val includeClueless: Boolean,
    /** When `true`, fall back to the word itself when no real clue exists (unblocks grid-api loading). */
    val placeholderClueFromWord: Boolean,
)

/** Port: stream export rows from the corpus, scoped to [block]. */
interface WordCorpusReader {
    fun <T> withExportRows(
        criteria: ExportSelectionCriteria,
        block: (Sequence<ExportRow>) -> T,
    ): T
}

/** Port: persistent destination for exported rows. */
interface WordCorpusExportSink {
    /** Writes [rows] in order. Returns the count actually written. */
    fun write(rows: Sequence<ExportRow>): Int
}
