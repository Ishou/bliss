package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.WordDifficultyRecomputer
import com.bliss.grid.domain.lexicon.WordFrequency
import com.bliss.grid.domain.lexicon.WordFrequencyUpdater

/**
 * `import-frequencies` use case: apply external frequency counts to existing
 * `words` rows for [language], then recompute difficulty from the new ranking
 * (ADR-0013 §4).
 */
class ImportFrequenciesUseCase(
    private val updater: WordFrequencyUpdater,
    private val recomputer: WordDifficultyRecomputer,
) {
    fun execute(
        frequencies: List<WordFrequency>,
        language: String,
    ): ImportFrequenciesReport {
        val updated = updater.applyFrequencies(language, frequencies.asSequence())
        val recomputed = recomputer.recomputeDifficulty(language)
        return ImportFrequenciesReport(
            entries = frequencies.size,
            updated = updated,
            recomputed = recomputed,
        )
    }
}

data class ImportFrequenciesReport(
    val entries: Int,
    val updated: Int,
    val recomputed: Int,
)
