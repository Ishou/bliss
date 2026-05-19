package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.CellDto
import com.bliss.grid.api.dto.DifficultyDto
import com.bliss.grid.api.dto.ListDailyPuzzlesResponseDto
import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.dto.PuzzleSummaryDto
import com.bliss.grid.api.dto.RevealCellHintRequest
import com.bliss.grid.api.dto.RevealCellHintResult
import com.bliss.grid.api.dto.ValidatePuzzleRequest
import com.bliss.grid.api.dto.ValidatePuzzleResult
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.application.auth.CookieVerifier
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.FilledCellInput
import com.bliss.grid.application.puzzle.HintUsageRepository
import com.bliss.grid.application.puzzle.HintWriteCoordinator
import com.bliss.grid.application.puzzle.ListDailyPuzzlesUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.RevealCellHintOutcome
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleOutcome
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.domain.generation.ClueCooldownRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
private const val NO_DAILY_PUZZLE_TYPE: String =
    "https://bliss.example/errors/no-daily-puzzle"
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
private const val AUTH_REQUIRED_TYPE: String =
    "https://bliss.example/errors/auth-required"

private const val SESSION_COOKIE_NAME: String = "__Secure-ws_session"

/** Grid bounded-context HTTP surface (ADR-0003 §4): puzzle GET/daily-GET, hint POST (authed-only), validate POST. */
fun Route.puzzles(
    loadOrGenerate: LoadOrGeneratePuzzleUseCase,
    revealCellHint: RevealCellHintUseCase,
    validatePuzzle: ValidatePuzzleUseCase,
    puzzleRepository: PuzzleRepository,
    hintUsageRepository: HintUsageRepository,
    hintWriteCoordinator: HintWriteCoordinator,
    cookieVerifier: CookieVerifier,
    listDailyPuzzles: ListDailyPuzzlesUseCase = ListDailyPuzzlesUseCase(puzzleRepository),
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
        // Pure read of the persisted daily row (ADR-0042); Dispatchers.IO keeps JDBC off the event loop.
        val stored = withContext(Dispatchers.IO) { puzzleRepository.get(puzzleId) }
        if (stored == null) {
            call.respondProblem(
                status = HttpStatusCode.NotFound,
                title = "Aucune grille du jour disponible",
                type = NO_DAILY_PUZZLE_TYPE,
                detail = "La grille du jour n'a pas encore été générée pour cette date.",
            )
            return@get
        }
        val difficulty = DifficultyDto.fromWire(dailyPuzzleSelector.difficultyForDate(date))
        val rawGridNumber = dailyPuzzleSelector.gridNumberForDate(date)
        val gridNumber = if (rawGridNumber >= 1) rawGridNumber else null
        val hintsRemaining =
            remainingHintsFor(
                cookieVerifier = cookieVerifier,
                hintUsageRepository = hintUsageRepository,
                rawCookie = call.request.cookies[SESSION_COOKIE_NAME],
                puzzleId = puzzleId,
                hintsAllowed = stored.hintsAllowed,
            )
        call.respond(
            mapper.toApi(
                grid = stored.grid,
                puzzleId = puzzleId,
                createdAt = stored.createdAt,
                hintsAllowed = stored.hintsAllowed,
                hintsRemaining = hintsRemaining,
                title = stored.title,
                language = stored.language,
                difficulty = difficulty,
                gridNumber = gridNumber,
            ),
        )
    }

    get("/v1/puzzles/daily/list") {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val from =
            when (val parsed = parseOptionalDate(call.parameters["from"], "from")) {
                is DateParse.Invalid -> {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Date invalide",
                        type = INVALID_PUZZLE_DATE_TYPE,
                        detail = parsed.detail,
                    )
                    return@get
                }
                is DateParse.Ok -> parsed.value
            }
        val to =
            when (val parsed = parseOptionalDate(call.parameters["to"], "to")) {
                is DateParse.Invalid -> {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Date invalide",
                        type = INVALID_PUZZLE_DATE_TYPE,
                        detail = parsed.detail,
                    )
                    return@get
                }
                is DateParse.Ok -> parsed.value
            }

        val started = clock.millis()
        val result = withContext(Dispatchers.IO) { listDailyPuzzles.execute(from = from, to = to, today = today) }
        val items =
            result.items.map { item ->
                PuzzleSummaryDto(
                    id = item.id.toString(),
                    date = item.date.toString(),
                    gridNumber = item.gridNumber,
                    difficulty = item.difficulty?.let(DifficultyDto::fromWire),
                    totalLetterCells = item.totalLetterCells,
                )
            }
        call.respond(ListDailyPuzzlesResponseDto(items = items, hasMore = result.hasMore))
        log.info(
            "list_daily_puzzles from={} to={} items_returned={} has_more={} latency_ms={}",
            from ?: "(default)",
            to ?: "(default)",
            items.size,
            result.hasMore,
            clock.millis() - started,
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

        val rawSession = call.request.headers["X-Session-Id"]
        val sessionId =
            if (rawSession.isNullOrBlank()) {
                null
            } else {
                val parsed = parseUuid(rawSession)
                val detail =
                    when {
                        parsed == null ->
                            "L'en-tête X-Session-Id doit être un UUID, reçu : '$rawSession'."
                        parsed == ClueCooldownRepository.DAILY_SCOPE_ID ->
                            "La valeur '$rawSession' est la sentinelle réservée au bucket quotidien " +
                                "et ne peut pas être utilisée comme identifiant de session."
                        else -> null
                    }
                if (detail != null) {
                    call.respondProblem(
                        status = HttpStatusCode.BadRequest,
                        title = "Identifiant de session invalide",
                        type = INVALID_SESSION_ID_TYPE,
                        detail = detail,
                    )
                    return@get
                }
                parsed
            }

        val stored = withContext(Dispatchers.IO) { loadOrGenerate.execute(puzzleId, width, height, sessionId) }
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
        val hintsRemaining =
            remainingHintsFor(
                cookieVerifier = cookieVerifier,
                hintUsageRepository = hintUsageRepository,
                rawCookie = call.request.cookies[SESSION_COOKIE_NAME],
                puzzleId = puzzleId,
                hintsAllowed = stored.hintsAllowed,
            )
        call.respond(
            mapper.toApi(
                grid = stored.grid,
                puzzleId = puzzleId,
                createdAt = stored.createdAt,
                hintsAllowed = stored.hintsAllowed,
                hintsRemaining = hintsRemaining,
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

        val rawCookie = call.request.cookies[SESSION_COOKIE_NAME]
        val cached =
            cookieVerifier.verify(rawCookie) ?: run {
                call.respondProblem(
                    status = HttpStatusCode.Unauthorized,
                    title = "Authentification requise",
                    type = AUTH_REQUIRED_TYPE,
                    detail = "Cette action nécessite une session valide.",
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

        val outcome =
            hintWriteCoordinator.withUserLock(cached.userId) { conn ->
                val fresh = cookieVerifier.verifyFresh(rawCookie)
                if (fresh == null || fresh.userId != cached.userId) {
                    RevealCellHintOutcome.SessionRevoked
                } else {
                    withContext(Dispatchers.IO) {
                        revealCellHint.execute(conn, puzzleId, fresh.userId, body.row, body.column)
                    }
                }
            }

        when (outcome) {
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
            is RevealCellHintOutcome.SessionRevoked ->
                call.respondProblem(
                    status = HttpStatusCode.Unauthorized,
                    title = "Session expirée",
                    type = AUTH_REQUIRED_TYPE,
                    detail = "Votre session a été invalidée.",
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
        when (val outcome = withContext(Dispatchers.IO) { validatePuzzle.execute(puzzleId, inputs) }) {
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

private suspend fun remainingHintsFor(
    cookieVerifier: CookieVerifier,
    hintUsageRepository: HintUsageRepository,
    rawCookie: String?,
    puzzleId: UUID,
    hintsAllowed: Int,
): Int {
    val who = cookieVerifier.verify(rawCookie) ?: return hintsAllowed
    val used = withContext(Dispatchers.IO) { hintUsageRepository.usedFor(puzzleId, who.userId) }
    return (hintsAllowed - used).coerceAtLeast(0)
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

private sealed interface DateParse {
    data class Ok(
        val value: LocalDate?,
    ) : DateParse

    data class Invalid(
        val detail: String,
    ) : DateParse
}

private fun parseOptionalDate(
    raw: String?,
    name: String,
): DateParse {
    if (raw.isNullOrBlank()) return DateParse.Ok(null)
    return try {
        DateParse.Ok(LocalDate.parse(raw))
    } catch (_: DateTimeParseException) {
        DateParse.Invalid(
            "Le paramètre $name doit être au format ISO-8601 YYYY-MM-DD, reçu : '$raw'.",
        )
    }
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
