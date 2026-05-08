package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.WordRepository
import java.text.Normalizer
import java.util.UUID

/**
 * Spends one hint to verify whether a candidate word is in the corpus.
 *
 * Flow:
 *  1. Validate the word against the wire `WordHintRequest.word` constraints
 *     (length 2..50, French letters + accents, no whitespace).
 *  2. Resolve the puzzle in the store — `PuzzleNotFound` if it has never
 *     been GET-ed.
 *  3. Spend a hint atomically against `(puzzleId, sessionId)` — `Exhausted`
 *     when the cap is already reached. Spend happens **before** corpus
 *     lookup so an exhausted client can't probe the dictionary by sending
 *     thousands of POSTs.
 *  4. Look up the normalized word in the corpus via
 *     `WordRepository.containsLemma`.
 *  5. Echo the server-normalized form (NFC + lowercase) per the wire spec.
 *
 * The wire response carries `word` as the server-normalized form so the
 * client can highlight the match it actually evaluated (handles cases where
 * the user submitted "FORÊT" and the server matched "forêt").
 */
class RequestWordHintUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val hintUsageRepository: HintUsageRepository,
    private val wordRepository: WordRepository,
) {
    fun execute(
        puzzleId: UUID,
        sessionId: UUID,
        rawWord: String,
    ): WordHintOutcome {
        val normalized = normalize(rawWord)
        if (!isValidWord(normalized)) {
            return WordHintOutcome.InvalidWord(
                "word must be 2..50 French letters (a-z, French diacritics); got '$rawWord'",
            )
        }
        val puzzle = puzzleRepository.get(puzzleId) ?: return WordHintOutcome.PuzzleNotFound
        val usedAfter =
            hintUsageRepository.trySpend(puzzleId, sessionId, puzzle.hintsAllowed)
                ?: return WordHintOutcome.BudgetExhausted
        val exists = wordRepository.containsLemma(normalized)
        return WordHintOutcome.Granted(
            word = normalized,
            exists = exists,
            hintsRemaining = puzzle.hintsAllowed - usedAfter,
        )
    }

    private fun normalize(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).lowercase()

    private fun isValidWord(word: String): Boolean = word.length in 2..50 && word.all { it in WORD_CHARS }

    companion object {
        private val WORD_CHARS: Set<Char> =
            buildSet {
                addAll('a'..'z')
                // French diacritics matching the OpenAPI pattern.
                addAll("àâäçéèêëîïôöùûüÿœæ".toList())
            }
    }
}

sealed class WordHintOutcome {
    /**
     * Hint spent successfully. [word] is the server-normalized form;
     * [hintsRemaining] is `hintsAllowed - hints_used after spend` (so 0
     * means the next call will return `BudgetExhausted`).
     */
    data class Granted(
        val word: String,
        val exists: Boolean,
        val hintsRemaining: Int,
    ) : WordHintOutcome()

    /** No puzzle in the store for this id. Maps to 404 puzzle-not-found. */
    data object PuzzleNotFound : WordHintOutcome()

    /** Per-(puzzle, session) cap reached. Maps to 429 hint-budget-exhausted. */
    data object BudgetExhausted : WordHintOutcome()

    /** Word violates length / character class. Maps to 400 invalid-word. */
    data class InvalidWord(
        val reason: String,
    ) : WordHintOutcome()
}
