package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.CellDto
import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.dto.ValidatePuzzleRequest
import com.bliss.grid.api.dto.ValidatePuzzleResult
import com.bliss.grid.api.dto.WordHintRequest
import com.bliss.grid.api.dto.WordHintResult
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.application.puzzle.FilledCellInput
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.RequestWordHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleOutcome
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.WordHintOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

// Mirrors minimum/maximum from PuzzleWidth/PuzzleHeight in openapi.yaml; keep in sync manually.
internal const val PUZZLE_MIN_DIMENSION: Int = 5
internal const val PUZZLE_MAX_DIMENSION: Int = 15

private const val INVALID_DIMENSIONS_TYPE: String =
    "https://bliss.example/errors/invalid-puzzle-dimensions"
private const val INVALID_PUZZLE_ID_TYPE: String =
    "https://bliss.example/errors/invalid-puzzle-id"
private const val PUZZLE_NOT_FOUND_TYPE: String =
    "https://bliss.example/errors/puzzle-not-found"
private const val PUZZLE_GENERATION_FAILED_TYPE: String =
    "https://bliss.example/errors/puzzle-generation-failed"
private const val INVALID_SESSION_ID_TYPE: String =
    "https://bliss.example/errors/invalid-session-id"
private const val INVALID_WORD_TYPE: String =
    "https://bliss.example/errors/invalid-word"
private const val INVALID_REQUEST_BODY_TYPE: String =
    "https://bliss.example/errors/invalid-request-body"
private const val INVALID_VALIDATE_REQUEST_TYPE: String =
    "https://bliss.example/errors/invalid-validate-request"
private const val HINT_BUDGET_EXHAUSTED_TYPE: String =
    "https://bliss.example/errors/hint-budget-exhausted"

/**
 * Grid bounded-context HTTP surface (ADR-0003 §4). Three endpoints:
 *  - GET  `/v1/puzzles/{puzzleId}` — idempotent puzzle fetch (lookup-or-generate).
 *  - POST `/v1/puzzles/{puzzleId}/hints` — spend a hint to corpus-check a word.
 *  - POST `/v1/puzzles/{puzzleId}/validate` — verify a filled grid against
 *    the canonical solution; returns position-only diff.
 *
 * GET-time persistence (`LoadOrGeneratePuzzleUseCase`) makes the puzzleId a
 * stable identifier: the first GET allocates and pins a grid, every
 * subsequent operation on the id reads the same grid back. The schema PR
 * (#218) made this a wire promise; the validate endpoint enforces it
 * server-side.
 */
fun Route.puzzles(
    loadOrGenerate: LoadOrGeneratePuzzleUseCase,
    requestWordHint: RequestWordHintUseCase,
    validatePuzzle: ValidatePuzzleUseCase,
    mapper: GridToPuzzleMapper = GridToPuzzleMapper(),
) {
    val log = LoggerFactory.getLogger("com.bliss.grid.api.routes.PuzzleRoute")

    get("/v1/puzzles/{puzzleId}") {
        val rawId = call.parameters["puzzleId"].orEmpty()
        val puzzleId =
            parseUuid(rawId) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid puzzle id",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Path parameter puzzleId must be a UUID, was '$rawId'.",
                )
                return@get
            }

        val width =
            when (val parsed = parseDimension(call.parameters["width"], "width")) {
                is DimensionParse.Invalid -> {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Invalid puzzle dimensions",
                        type = INVALID_DIMENSIONS_TYPE,
                        detail = parsed.detail,
                    )
                    return@get
                }
                is DimensionParse.Ok -> parsed.value
            }
        val height =
            when (val parsed = parseDimension(call.parameters["height"], "height")) {
                is DimensionParse.Invalid -> {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Invalid puzzle dimensions",
                        type = INVALID_DIMENSIONS_TYPE,
                        detail = parsed.detail,
                    )
                    return@get
                }
                is DimensionParse.Ok -> parsed.value
            }

        val stored = loadOrGenerate.execute(puzzleId, width, height)
        if (stored == null) {
            log.warn("puzzle_generation_failed puzzle_id={}", puzzleId)
            call.respondProblem(
                status = HttpStatusCode.UnprocessableEntity,
                title = "Puzzle generation failed",
                type = PUZZLE_GENERATION_FAILED_TYPE,
                detail = "The generator could not satisfy the requested constraints.",
            )
            return@get
        }
        call.respond(
            mapper.toApi(
                grid = stored.grid,
                puzzleId = puzzleId,
                createdAt = stored.createdAt,
                hintsAllowed = stored.hintsAllowed,
                title = stored.title,
                language = stored.language,
            ),
        )
    }

    post("/v1/puzzles/{puzzleId}/hints") {
        val rawId = call.parameters["puzzleId"].orEmpty()
        val puzzleId =
            parseUuid(rawId) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid puzzle id",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Path parameter puzzleId must be a UUID, was '$rawId'.",
                )
                return@post
            }

        val rawSession = call.request.headers["X-Session-Id"].orEmpty()
        val sessionId =
            parseUuid(rawSession) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid session id",
                    type = INVALID_SESSION_ID_TYPE,
                    detail = "X-Session-Id header must be a UUID; got '$rawSession'.",
                )
                return@post
            }

        val body =
            try {
                call.receive<WordHintRequest>()
            } catch (e: SerializationException) {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid request body",
                    type = INVALID_REQUEST_BODY_TYPE,
                    detail = e.message ?: "request body could not be deserialized as WordHintRequest",
                )
                return@post
            }

        when (val outcome = requestWordHint.execute(puzzleId, sessionId, body.word)) {
            is WordHintOutcome.Granted ->
                call.respond(
                    WordHintResult(
                        word = outcome.word,
                        exists = outcome.exists,
                        hintsRemaining = outcome.hintsRemaining,
                    ),
                )
            is WordHintOutcome.PuzzleNotFound ->
                call.respondProblem(
                    status = HttpStatusCode.NotFound,
                    title = "Puzzle not found",
                    type = PUZZLE_NOT_FOUND_TYPE,
                    detail = "No puzzle with id '$puzzleId'.",
                )
            is WordHintOutcome.BudgetExhausted ->
                call.respondProblem(
                    status = HttpStatusCode.TooManyRequests,
                    title = "Hint budget exhausted",
                    type = HINT_BUDGET_EXHAUSTED_TYPE,
                    detail = "Hint budget for this (puzzle, player) is already at the cap.",
                )
            is WordHintOutcome.InvalidWord ->
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid word",
                    type = INVALID_WORD_TYPE,
                    detail = outcome.reason,
                )
        }
    }

    post("/v1/puzzles/{puzzleId}/validate") {
        val rawId = call.parameters["puzzleId"].orEmpty()
        val puzzleId =
            parseUuid(rawId) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid puzzle id",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Path parameter puzzleId must be a UUID, was '$rawId'.",
                )
                return@post
            }

        val body =
            try {
                call.receive<ValidatePuzzleRequest>()
            } catch (e: SerializationException) {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid request body",
                    type = INVALID_REQUEST_BODY_TYPE,
                    detail = e.message ?: "request body could not be deserialized as ValidatePuzzleRequest",
                )
                return@post
            }

        val inputs = body.filledCells.map { FilledCellInput(it.row, it.column, it.letter) }
        when (val outcome = validatePuzzle.execute(puzzleId, inputs)) {
            is ValidatePuzzleOutcome.Result ->
                call.respond(
                    ValidatePuzzleResult(
                        solved = outcome.solved,
                        incorrectCells =
                            outcome.incorrectCells.map {
                                CellDto.PositionDto(row = it.row.value, column = it.column.value)
                            },
                    ),
                )
            is ValidatePuzzleOutcome.PuzzleNotFound ->
                call.respondProblem(
                    status = HttpStatusCode.NotFound,
                    title = "Puzzle not found",
                    type = PUZZLE_NOT_FOUND_TYPE,
                    detail = "No puzzle with id '$puzzleId'.",
                )
            is ValidatePuzzleOutcome.RequestInvalid ->
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Invalid validate request",
                    type = INVALID_VALIDATE_REQUEST_TYPE,
                    detail = outcome.reason,
                )
        }
    }
}

private sealed interface DimensionParse {
    data class Ok(
        val value: Int?,
    ) : DimensionParse

    data class Invalid(
        val detail: String,
    ) : DimensionParse
}

private fun parseDimension(
    raw: String?,
    name: String,
): DimensionParse {
    if (raw == null) return DimensionParse.Ok(null)
    val parsed =
        raw.toIntOrNull()
            ?: return DimensionParse.Invalid(
                "Query parameter '$name' must be an integer, was '$raw'.",
            )
    if (parsed !in PUZZLE_MIN_DIMENSION..PUZZLE_MAX_DIMENSION) {
        return DimensionParse.Invalid(
            "Query parameter '$name' must be between $PUZZLE_MIN_DIMENSION and " +
                "$PUZZLE_MAX_DIMENSION inclusive, was $parsed.",
        )
    }
    return DimensionParse.Ok(parsed)
}

private fun parseUuid(raw: String): UUID? =
    try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    type: String,
    detail: String?,
) {
    val problem =
        ProblemDetails(
            type = type,
            title = title,
            status = status.value,
            detail = detail,
            instance = request.local.uri,
        )
    respondText(
        text = Json.encodeToString(ProblemDetails.serializer(), problem),
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}
