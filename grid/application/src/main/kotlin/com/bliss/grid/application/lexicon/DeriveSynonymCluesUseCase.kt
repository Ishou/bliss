package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.ClueSource

/** Optionally truncates dbnary-synonym rows for the language, then runs SQL derivation. */
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
