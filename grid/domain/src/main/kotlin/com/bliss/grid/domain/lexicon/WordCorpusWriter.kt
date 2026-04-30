package com.bliss.grid.domain.lexicon

/**
 * Domain port for persisting surface forms into the lexicon corpus.
 * Adapter (e.g. JDBC against the `words` table) lives in infrastructure.
 */
interface WordCorpusWriter {
    /**
     * Inserts the given records. Implementations are expected to be idempotent on
     * `(word, language)` collisions — collisions count as `skipped`, not failures.
     */
    fun insertSurfaceForms(records: Sequence<SurfaceFormRecord>): InsertReport
}

/** A row to insert into the corpus. Difficulty is computed by the use case (ADR-0013 §4). */
data class SurfaceFormRecord(
    val word: String,
    val language: String,
    val difficulty: Float,
    val source: String,
    val sourceLicense: String,
)

/** How many of the provided records were inserted vs. skipped (already present). */
data class InsertReport(
    val inserted: Int,
    val skipped: Int,
)
