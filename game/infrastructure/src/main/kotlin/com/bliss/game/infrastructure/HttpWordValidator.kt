// HTTP adapter for the WordValidator port. Calls grid's
// `POST /v1/puzzles/{id}/validate` and translates the wire response into
// the set of incorrect positions.
//
// Why not derive from the puzzle's letter cells directly: per the v1
// wire (grid/api/openapi.yaml `LetterCell`), letter cells ship without
// their canonical answer — solutions are server-private. game-api
// therefore can't validate locally and must delegate to grid for every
// word check, mirroring the FE solo path's `PuzzleSolver.validate`.
package com.bliss.game.infrastructure

import com.bliss.game.application.ports.WordValidator
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Position
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

class HttpWordValidator(
    private val httpClient: HttpClient,
    private val gridBaseUrl: String,
    private val json: Json = DEFAULT_JSON,
) : WordValidator {
    override suspend fun incorrectPositions(
        puzzleId: UUID,
        filled: Map<Position, Letter>,
    ): Set<Position> {
        val request =
            ValidateRequestDto(
                filledCells =
                    filled.entries.map { (pos, letter) ->
                        FilledCellDto(row = pos.row, column = pos.column, letter = letter.value.toString())
                    },
            )
        val response: HttpResponse =
            try {
                httpClient.post("$gridBaseUrl/v1/puzzles/$puzzleId/validate") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(json.encodeToString(ValidateRequestDto.serializer(), request))
                }
            } catch (cause: IOException) {
                throw WordValidatorException.UpstreamUnavailable(cause)
            }
        if (!response.status.isSuccess()) {
            throw WordValidatorException.UpstreamError(response.status.value, response.bodyAsText())
        }
        val dto =
            try {
                json.decodeFromString(ValidateResponseDto.serializer(), response.bodyAsText())
            } catch (cause: SerializationException) {
                throw WordValidatorException.UpstreamMalformed(cause)
            }
        return dto.incorrectCells.map { Position(it.row, it.column) }.toSet()
    }

    companion object {
        internal val DEFAULT_JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

@Serializable
private data class ValidateRequestDto(
    val filledCells: List<FilledCellDto>,
)

@Serializable
private data class FilledCellDto(
    val row: Int,
    val column: Int,
    val letter: String,
)

@Serializable
private data class ValidateResponseDto(
    val solved: Boolean,
    val incorrectCells: List<IncorrectCellDto>,
)

@Serializable
private data class IncorrectCellDto(
    val row: Int,
    val column: Int,
)

sealed class WordValidatorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UpstreamError(
        val status: Int,
        val body: String,
    ) : WordValidatorException("grid validate responded with HTTP $status: $body")

    class UpstreamUnavailable(
        cause: Throwable,
    ) : WordValidatorException("grid validate is unreachable: ${cause.message}", cause)

    class UpstreamMalformed(
        cause: Throwable,
    ) : WordValidatorException("grid validate response failed to parse: ${cause.message}", cause)
}
