package com.bliss.game.application.ports

/**
 * Failure modes raised by [PuzzleProvider] implementations. Application use cases
 * translate these into [com.bliss.game.application.usecases.UseCaseError] — the
 * exception must live here so both sides can reference it without an infrastructure import.
 */
sealed class PuzzleProviderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UpstreamError(
        val status: Int,
        val body: String,
    ) : PuzzleProviderException("grid responded with HTTP $status")

    class UpstreamUnavailable(
        cause: Throwable,
    ) : PuzzleProviderException("grid is unreachable: ${cause.message}", cause)

    class UpstreamMalformed(
        cause: Throwable,
    ) : PuzzleProviderException("grid response failed to parse: ${cause.message}", cause)
}
