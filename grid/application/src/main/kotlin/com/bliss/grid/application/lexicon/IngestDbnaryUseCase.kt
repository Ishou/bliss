package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.DbnaryWord

/**
 * `ingest-dbnary` use case: optionally truncate the language, then upsert
 * the parsed DBnary entries into the offline scratch tables (ADR-0023).
 *
 * Truncate-before-upsert is the workflow for clean re-ingest after a fetch
 * round; without `--truncate`, upsert is purely additive (existing entries
 * with matching `(language, lemma, pos)` get their senses + synonyms replaced
 * in place).
 */
class IngestDbnaryUseCase(
    private val repository: DbnaryRepository,
) {
    fun execute(
        entries: Sequence<DbnaryWord>,
        language: String,
        truncate: Boolean,
    ): IngestDbnaryReport {
        require(language.isNotBlank()) { "language must not be blank" }
        val deleted = if (truncate) repository.deleteByLanguage(language) else 0
        val report = repository.upsertAll(entries)
        return IngestDbnaryReport(
            deletedFromTruncate = deleted,
            wordsInserted = report.wordsInserted,
            wordsUpdated = report.wordsUpdated,
            sensesWritten = report.sensesWritten,
            synonymsWritten = report.synonymsWritten,
        )
    }
}

data class IngestDbnaryReport(
    val deletedFromTruncate: Int,
    val wordsInserted: Int,
    val wordsUpdated: Int,
    val sensesWritten: Int,
    val synonymsWritten: Int,
)
