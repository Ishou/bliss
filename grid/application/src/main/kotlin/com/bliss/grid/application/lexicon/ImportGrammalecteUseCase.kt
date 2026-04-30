package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.GrammalecteCorpusWriter
import com.bliss.grid.domain.lexicon.GrammalecteEntry
import com.bliss.grid.domain.lexicon.WordDifficultyRecomputer

/**
 * `import-grammalecte` use case: optionally truncate the language, bulk-insert
 * the parsed lexique, then recompute difficulty from the new ranking
 * (ADR-0013 §4).
 */
class ImportGrammalecteUseCase(
    private val writer: GrammalecteCorpusWriter,
    private val recomputer: WordDifficultyRecomputer,
) {
    fun execute(
        entries: Map<String, GrammalecteEntry>,
        language: String,
        source: String,
        sourceLicense: String,
        truncate: Boolean,
    ): ImportGrammalecteReport {
        val deleted = if (truncate) writer.truncateLanguage(language) else 0
        val inserted = writer.insertLexique(language, source, sourceLicense, entries.values.asSequence())
        val recomputed = recomputer.recomputeDifficulty(language)
        return ImportGrammalecteReport(
            uniqueForms = entries.size,
            deleted = deleted,
            inserted = inserted,
            recomputed = recomputed,
        )
    }
}

data class ImportGrammalecteReport(
    val uniqueForms: Int,
    val deleted: Int,
    val inserted: Int,
    val recomputed: Int,
)
