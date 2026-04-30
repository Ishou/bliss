package com.bliss.grid.domain.lexicon

/**
 * Port: recomputes `difficulty` for every row of the given language from the current
 * frequency ranking (ADR-0013 §4 sigmoid). Adapter is SQL-side: cheaper to compute
 * the whole language in one statement than per-row.
 */
interface WordDifficultyRecomputer {
    fun recomputeDifficulty(language: String): Int
}
