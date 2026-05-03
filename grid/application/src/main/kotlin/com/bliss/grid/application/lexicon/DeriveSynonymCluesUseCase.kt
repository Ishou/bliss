package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.ClueSource

/**
 * `derive-synonym-clues` use case: optionally truncate the existing
 * `dbnary-synonym` candidates for a language, then run the SQL derivation
 * that joins `words` with `dbnary_synonyms` via the lemma key.
 *
 * Truncate-before-derive is the workflow for clean re-runs after a fresh
 * DBnary ingest; without `--truncate`, the V6 NULLS NOT DISTINCT unique
 * constraint and the underlying ON CONFLICT DO NOTHING make the derivation
 * additive (only newly-discovered synonyms add rows).
 */
class DeriveSynonymCluesUseCase(
    private val repository: ClueCandidateRepository,
) {
    fun execute(
        language: String,
        truncate: Boolean,
    ): DeriveSynonymCluesReport {
        require(language.isNotBlank()) { "language must not be blank" }
        val deleted =
            if (truncate) repository.deleteBySource(ClueSource.DBNARY_SYNONYM, language) else 0
        val derived = repository.deriveSynonymClues(language)
        return DeriveSynonymCluesReport(deleted = deleted, inserted = derived)
    }
}

data class DeriveSynonymCluesReport(
    val deleted: Int,
    val inserted: Int,
)
