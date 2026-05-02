// HTTP adapter for the PuzzleProvider port. Calls grid's
// `GET /v1/puzzles/{puzzleId}?width&height` and translates the wire response into
// a game:domain GamePuzzle. Per ADR-0001 §1, the wire shape (PuzzleResponseDto)
// is owned by this module — no cross-context import.
package com.bliss.game.infrastructure

import com.bliss.game.application.ports.PuzzleProvider
import com.bliss.game.domain.GamePuzzle
import com.fasterxml.uuid.Generators
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

/**
 * HTTP adapter for [PuzzleProvider] that calls grid's `GET /v1/puzzles/{id}`.
 *
 * @param httpClient Ktor client; this adapter uses [bodyAsText] + [Json.decodeFromString]
 *   so a vanilla client (no ContentNegotiation plugin) is sufficient.
 * @param gridBaseUrl scheme + host + port, no trailing slash
 *   (e.g. `http://grid-api.bliss.svc.cluster.local:8080`).
 */
class HttpPuzzleProvider(
    private val httpClient: HttpClient,
    private val gridBaseUrl: String,
    private val json: Json = DEFAULT_JSON,
) : PuzzleProvider {
    // UUID v7 — time-sortable. Matches grid:api/GridToPuzzleMapper's choice (ADR-0003 §6).
    private val uuidGenerator = Generators.timeBasedEpochGenerator()

    override suspend fun fetch(
        width: Int,
        height: Int,
    ): GamePuzzle {
        val response = call(uuidGenerator.generate(), width, height)
        if (!response.status.isSuccess()) {
            throw PuzzleProviderException.UpstreamError(response.status.value, response.bodyAsText())
        }
        val dto =
            try {
                json.decodeFromString(PuzzleResponseDto.serializer(), response.bodyAsText())
            } catch (cause: SerializationException) {
                throw PuzzleProviderException.UpstreamMalformed(cause)
            }
        // Domain init blocks (e.g. invalid title length, out-of-bounds cell, bad enum)
        // surface as malformed upstream — the wire payload doesn't satisfy our invariants.
        return try {
            dto.toDomain()
        } catch (cause: IllegalArgumentException) {
            throw PuzzleProviderException.UpstreamMalformed(cause)
        }
    }

    private suspend fun call(
        puzzleId: UUID,
        width: Int,
        height: Int,
    ): HttpResponse =
        try {
            httpClient.get("$gridBaseUrl/v1/puzzles/$puzzleId") {
                parameter("width", width)
                parameter("height", height)
            }
        } catch (cause: IOException) {
            throw PuzzleProviderException.UpstreamUnavailable(cause)
        }

    companion object {
        // Lenient: tolerate forward-compatible field additions and ignore explicit nulls.
        internal val DEFAULT_JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

/**
 * Failure modes raised by [HttpPuzzleProvider]. The application layer translates these to
 * [com.bliss.game.application.usecases.UseCaseError] in StartGameUseCase (Wave D · PR #4).
 */
sealed class PuzzleProviderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UpstreamError(
        val status: Int,
        val body: String,
    ) : PuzzleProviderException("grid responded with HTTP $status: ${HttpStatusCode.fromValue(status).description}")

    class UpstreamUnavailable(
        cause: Throwable,
    ) : PuzzleProviderException("grid is unreachable: ${cause.message}", cause)

    class UpstreamMalformed(
        cause: Throwable,
    ) : PuzzleProviderException("grid response failed to parse: ${cause.message}", cause)
}
