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
    /** Dictionary headword for [word]; equals [word] for lemma forms themselves. */
    val lemma: String,
)

/** Selection knobs for the export query. */
data class ExportSelectionCriteria(
    val language: String,
    /** When `true`, drop the `clue IS NOT NULL` filter. */
    val includeClueless: Boolean,
    /** When `true`, fall back to the word itself when no real clue exists (unblocks grid-api loading). */
    val placeholderClueFromWord: Boolean,
    /**
     * Source priority for `clue_candidates` lookup at export time. When non-empty,
     * each word's exported clue/source is overlaid with the highest-priority
     * matching candidate; words without a matching candidate keep the legacy
     * `words.clue` value. Empty list disables the lookup (legacy behaviour).
     *
     * Phase 2 §4 of the clue-generation pipeline plan; see also ADR-0024.
     */
    val candidateSourcePriority: List<String> = emptyList(),
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
