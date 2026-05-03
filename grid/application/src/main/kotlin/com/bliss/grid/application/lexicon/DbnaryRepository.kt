// Application port for the DBnary scratch tables (dbnary_words / dbnary_senses /
// dbnary_synonyms). Per ADR-0023 these are offline-pipeline scratch space —
// neither readable from API code paths nor exported to the product surface.
// The JDBC adapter lives in infrastructure.
package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.DbnaryWord

interface DbnaryRepository {
    /**
     * Persist a batch of DBnary entries idempotently, keyed by
     * `(language, lemma, pos)`. Re-ingesting the same key REPLACES the entry's
     * sense list and synonym list (delete-then-insert) so partial state from
     * an earlier run never leaks into the next.
     *
     * Caller is responsible for chunking very large inputs; one call to this
     * method runs in a single transaction so all-or-nothing semantics hold per
     * batch.
     */
    fun upsertAll(entries: Sequence<DbnaryWord>): UpsertReport

    /**
     * Delete every entry (and via cascade every sense + synonym) for the
     * language. Returns the number of `dbnary_words` rows removed. Used by
     * the worker's `--truncate` flag for clean re-ingest.
     */
    fun deleteByLanguage(language: String): Int

    /**
     * Look up a single entry by its natural key. Senses come back ordered by
     * `senseIndex` ascending — DBnary's original order, the same one
     * `enrich_with_morphology.py`'s self-reference filter walks.
     */
    fun findOne(
        language: String,
        lemma: String,
        pos: String,
    ): DbnaryWord?

    /** Number of `dbnary_words` rows for the language. Cheap; for smoke checks. */
    fun countByLanguage(language: String): Long
}

/**
 * Result of [DbnaryRepository.upsertAll].
 *
 * `wordsInserted` and `wordsUpdated` distinguish first-time inserts from
 * re-ingest of an existing key (Postgres `xmax = 0` trick). `sensesWritten`
 * and `synonymsWritten` count the rows newly inserted after the per-word
 * delete-then-insert, so they reflect the post-batch state, not the delta.
 */
data class UpsertReport(
    val wordsInserted: Int,
    val wordsUpdated: Int,
    val sensesWritten: Int,
    val synonymsWritten: Int,
)
