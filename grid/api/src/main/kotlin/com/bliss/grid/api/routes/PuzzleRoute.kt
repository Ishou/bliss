package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.CellDto
import com.bliss.grid.api.dto.DifficultyDto
import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.dto.RevealCellHintRequest
import com.bliss.grid.api.dto.RevealCellHintResult
import com.bliss.grid.api.dto.ValidatePuzzleRequest
import com.bliss.grid.api.dto.ValidatePuzzleResult
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.FilledCellInput
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.RevealCellHintOutcome
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleOutcome
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

// Mirrors minimum/maximum from PuzzleWidth/PuzzleHeight in openapi.yaml; keep in sync manually.
internal const val PUZZLE_MIN_DIMENSION: Int = 5
internal const val PUZZLE_MAX_DIMENSION: Int = 15

private const val INVALID_DIMENSIONS_TYPE: String =
    "https://bliss.example/errors/invalid-puzzle-dimensions"
private const val INVALID_PUZZLE_ID_TYPE: String =
    "https://bliss.example/errors/invalid-puzzle-id"
private const val INVALID_PUZZLE_DATE_TYPE: String =
    "https://bliss.example/errors/invalid-puzzle-date"
private const val PUZZLE_NOT_FOUND_TYPE: String =
    "https://bliss.example/errors/puzzle-not-found"
private const val PUZZLE_GENERATION_FAILED_TYPE: String =
    "https://bliss.example/errors/puzzle-generation-failed"
private const val INVALID_SESSION_ID_TYPE: String =
    "https://bliss.example/errors/invalid-session-id"
private const val INVALID_COORD_TYPE: String =
    "https://bliss.example/errors/invalid-coord"
private const val INVALID_REQUEST_BODY_TYPE: String =
    "https://bliss.example/errors/invalid-request-body"
private const val INVALID_VALIDATE_REQUEST_TYPE: String =
    "https://bliss.example/errors/invalid-validate-request"
private const val HINT_BUDGET_EXHAUSTED_TYPE: String =
    "https://bliss.example/errors/hint-budget-exhausted"

/**
 * Grid bounded-context HTTP surface (ADR-0003 §4). Three endpoints:
 *  - GET  `/v1/puzzles/{puzzleId}` — idempotent puzzle fetch (lookup-or-generate).
 *  - POST `/v1/puzzles/{puzzleId}/hints` — spend a hint to reveal the
 *    canonical letter at a `(row, column)` cell.
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
    revealCellHint: RevealCellHintUseCase,
    validatePuzzle: ValidatePuzzleUseCase,
    mapper: GridToPuzzleMapper = GridToPuzzleMapper(),
    dailyPuzzleSelector: DailyPuzzleSelector = DailyPuzzleSelector(),
    clock: Clock = Clock.systemUTC(),
) {
    val log = LoggerFactory.getLogger("com.bliss.grid.api.routes.PuzzleRoute")

    get("/v1/puzzles/daily") {
        val rawDate = call.parameters["date"]
        val date =
            if (rawDate.isNullOrBlank()) {
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            } else {
                try {
                    LocalDate.parse(rawDate)
                } catch (_: DateTimeParseException) {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Date invalide",
                        type = INVALID_PUZZLE_DATE_TYPE,
                        detail = "Le paramètre date doit être au format ISO-8601 YYYY-MM-DD, reçu : '$rawDate'.",
                    )
                    return@get
                }
            }

        val puzzleId = dailyPuzzleSelector.puzzleIdForDate(date)
        val stored = loadOrGenerate.execute(puzzleId)
        if (stored == null) {
            log.warn("daily_puzzle_generation_failed date={} puzzle_id={}", date, puzzleId)
            call.respondProblem(
                status = HttpStatusCode.UnprocessableEntity,
                title = "Échec de la génération de grille",
                type = PUZZLE_GENERATION_FAILED_TYPE,
                detail = "Le générateur n'a pas pu satisfaire les contraintes demandées.",
            )
            return@get
        }
        val difficulty = DifficultyDto.fromWire(dailyPuzzleSelector.difficultyForDate(date))
        call.respond(
            mapper.toApi(
                grid = stored.grid,
                puzzleId = puzzleId,
                createdAt = stored.createdAt,
                hintsAllowed = stored.hintsAllowed,
                title = stored.title,
                language = stored.language,
                difficulty = difficulty,
                gridNumber = dailyPuzzleSelector.gridNumberForDate(date),
            ),
        )
    }

    get("/v1/puzzles/{puzzleId}") {
        val rawId = call.parameters["puzzleId"].orEmpty()
        val puzzleId =
            parseUuid(rawId) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Identifiant de grille invalide",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Le paramètre puzzleId doit être un UUID, reçu : '$rawId'.",
                )
                return@get
            }

        val width =
            when (val parsed = parseDimension(call.parameters["width"], "width")) {
                is DimensionParse.Invalid -> {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Dimensions de grille invalides",
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
                        title = "Dimensions de grille invalides",
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
                title = "Échec de la génération de grille",
                type = PUZZLE_GENERATION_FAILED_TYPE,
                detail = "Le générateur n'a pas pu satisfaire les contraintes demandées.",
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
                    title = "Identifiant de grille invalide",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Le paramètre puzzleId doit être un UUID, reçu : '$rawId'.",
                )
                return@post
            }

        val rawSession = call.request.headers["X-Session-Id"].orEmpty()
        val sessionId =
            parseUuid(rawSession) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Identifiant de session invalide",
                    type = INVALID_SESSION_ID_TYPE,
                    detail = "L'en-tête X-Session-Id doit être un UUID, reçu : '$rawSession'.",
                )
                return@post
            }

        val body =
            try {
                call.receive<RevealCellHintRequest>()
            } catch (e: SerializationException) {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Corps de requête invalide",
                    type = INVALID_REQUEST_BODY_TYPE,
                    detail = e.message ?: "request body could not be deserialized as RevealCellHintRequest",
                )
                return@post
            }

        when (val outcome = revealCellHint.execute(puzzleId, sessionId, body.row, body.column)) {
            is RevealCellHintOutcome.Granted ->
                call.respond(
                    RevealCellHintResult(
                        row = outcome.row,
                        column = outcome.column,
                        letter = outcome.letter.toString(),
                        hintsRemaining = outcome.hintsRemaining,
                    ),
                )
            is RevealCellHintOutcome.PuzzleNotFound ->
                call.respondProblem(
                    status = HttpStatusCode.NotFound,
                    title = "Grille introuvable",
                    type = PUZZLE_NOT_FOUND_TYPE,
                    detail = "Aucune grille pour l'identifiant '$puzzleId'.",
                )
            is RevealCellHintOutcome.BudgetExhausted ->
                call.respondProblem(
                    status = HttpStatusCode.TooManyRequests,
                    title = "Quota d'indices épuisé",
                    type = HINT_BUDGET_EXHAUSTED_TYPE,
                    detail = "Le quota d'indices pour cette grille a déjà été atteint.",
                )
            is RevealCellHintOutcome.InvalidCoord ->
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Coordonnées invalides",
                    type = INVALID_COORD_TYPE,
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
                    title = "Identifiant de grille invalide",
                    type = INVALID_PUZZLE_ID_TYPE,
                    detail = "Le paramètre puzzleId doit être un UUID, reçu : '$rawId'.",
                )
                return@post
            }

        val body =
            try {
                call.receive<ValidatePuzzleRequest>()
            } catch (e: SerializationException) {
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Corps de requête invalide",
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
                    title = "Grille introuvable",
                    type = PUZZLE_NOT_FOUND_TYPE,
                    detail = "Aucune grille pour l'identifiant '$puzzleId'.",
                )
            is ValidatePuzzleOutcome.RequestInvalid ->
                call.respondProblem(
                    status = HttpStatusCode.BadRequest,
                    title = "Requête de validation invalide",
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
