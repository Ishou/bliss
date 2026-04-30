// Domain port for an external clue-generation provider (ADR-0013 §5).
package com.bliss.grid.domain.clue

/** Result of one [ClueClient.generateClue] call. */
sealed class ClueResult {
    /** Provider returned an acceptable clue (length-validated upstream). */
    data class Accepted(val clue: String) : ClueResult()

    /** Provider returned a clue that exceeds the length budget; caller decides whether to retry. */
    data class TooLong(val rejectedClue: String) : ClueResult()

    /** Provider call failed (network, 4xx/5xx, malformed response). */
    data class ApiError(val cause: Throwable) : ClueResult()
}

/**
 * Generates one clue per word. Implementations are infrastructure adapters
 * (Anthropic, OpenAI, fakes) — application code talks to this port only.
 */
interface ClueClient {
    /**
     * Generate a clue for [word]. If [retry] is true, the implementation should
     * use a stricter re-prompt (ADR-0013 §5).
     */
    suspend fun generateClue(
        word: String,
        retry: Boolean = false,
    ): ClueResult
}
