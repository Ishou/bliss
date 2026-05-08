package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word

interface WordRepository {
    fun findByLength(length: Int): List<Word>

    fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word>

    /**
     * Whether the supplied text is a known lemma in the corpus. Implementations
     * MUST normalize accents/case before lookup (the user-facing layer accepts
     * raw French input like "forêt" or "FORÊT"); a match against either the
     * folded surface form or the folded lemma counts as a hit. Used by the
     * hint endpoint to verify a player's candidate word without leaking the
     * canonical solution.
     */
    fun containsLemma(text: String): Boolean
}
