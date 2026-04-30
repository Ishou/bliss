package com.bliss.grid.domain.lexicon

/** Port: applies frequency counts to existing rows. */
interface WordFrequencyUpdater {
    /** Updates `frequency` on each matching `(word, language)`. Returns rows updated. */
    fun applyFrequencies(language: String, frequencies: Sequence<WordFrequency>): Int
}
