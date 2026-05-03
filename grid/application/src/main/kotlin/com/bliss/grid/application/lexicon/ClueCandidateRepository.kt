// Application port for the `clue_candidates` table (V6 migration). The
// JDBC adapter lives in infrastructure; the worker batch jobs and the export
// pipeline read/write through this interface.
package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.ClueCandidate
import java.util.UUID

interface ClueCandidateRepository {
    /**
     * Persist a batch of candidates idempotently. The table's UNIQUE key is
     * `(word_id, source, sense_index, clue_text)` — re-running with the same
     * key UPDATES `confidence`, `model_version`, and `generated_at` so a
     * fresh model run can refresh those fields without duplicating the row.
     *
     * Caller chunks for memory; one call runs in a single transaction so all-
     * or-nothing semantics hold per batch.
     */
    fun upsertAll(candidates: Sequence<ClueCandidate>): UpsertCandidatesReport

    /**
     * Delete every row matching `source`, optionally restricted to a
     * `language` (joined via the `words` table). Used to wipe a source ahead
     * of regeneration: e.g. `deleteBySource("mistral-7b-base", "fr")`.
     * Returns the number of rows removed.
     */
    fun deleteBySource(
        source: String,
        language: String? = null,
    ): Int

    /**
     * All candidates for `wordId`, ordered by source ASC then generated_at
     * DESC. The export step uses [findTopBySourcePriority] for the actual
     * pick — this method is for inspection / logging.
     */
    fun findByWord(wordId: UUID): List<ClueCandidate>

    /**
     * The single candidate to publish for `wordId`, picked by walking
     * `sourcePriority` in order: first source that has a row wins. Within a
     * source, ties break by `confidence DESC NULLS LAST`, then
     * `generated_at DESC`.
     *
     * Returns null when no candidate exists for `wordId` from ANY of the
     * priority sources.
     */
    fun findTopBySourcePriority(
        wordId: UUID,
        sourcePriority: List<String>,
    ): ClueCandidate?

    /** Smoke-check count for a source. */
    fun countBySource(source: String): Long

    /** Derives dbnary-synonym candidates via SQL join; filters self-refs and len>80; idempotent (ON CONFLICT DO NOTHING). Returns new-row count. */
    fun deriveSynonymClues(language: String): Int

    /**
     * Bulk lookup: for each `lemma` in [lemmas], return the `word_id` of the
     * matching row in `words` whose `(language, word)` equals `(language, lemma)`
     * — i.e., the word_id of the lemma's citation form.
     *
     * Used by `ingest-clue-candidates` to map per-lemma CSV rows (which carry
     * lemma strings) onto `clue_candidates.word_id` (a UUID). Lemmas not in
     * the corpus are silently absent from the returned map.
     */
    fun findLemmaWordIds(
        language: String,
        lemmas: Collection<String>,
    ): Map<String, java.util.UUID>
}

/**
 * Result of [ClueCandidateRepository.upsertAll].
 *
 * `inserted` and `updated` distinguish first-time inserts from re-upsert of
 * an existing key (Postgres `xmax = 0` trick on the RETURNING clause).
 */
data class UpsertCandidatesReport(
    val inserted: Int,
    val updated: Int,
)
