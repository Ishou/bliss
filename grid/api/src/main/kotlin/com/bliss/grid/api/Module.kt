package com.bliss.grid.api

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.infrastructure.Database
import com.bliss.grid.api.routes.deleteSession
import com.bliss.grid.api.routes.health
import com.bliss.grid.api.routes.puzzles
import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.DeleteSessionUseCase
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.HintUsageRepository
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.infrastructure.analytics.MatomoAnalyticsAdapter
import com.bliss.grid.infrastructure.analytics.NoopAnalyticsAdapter
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import com.bliss.grid.infrastructure.persistence.InMemoryClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import com.bliss.grid.infrastructure.persistence.PostgresClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.PostgresHintUsageRepository
import com.bliss.grid.infrastructure.persistence.PostgresPuzzleRepository
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Wires CORS, content negotiation (JSON), call logging, RFC 7807 errors, and routes. */
fun Application.module() {
    // Apply Flyway migrations against the CNPG-backed database (ADR-0013 §6,
    // ADR-0009). No-op when DATABASE_URL is unset (local dev, CI smoke tests).
    Database.start()

    install(CORS) {
        // Browsers block `https://wordsparrow.io` → `https://api.wordsparrow.io`
        // without these headers. Preview deploys do NOT call this API (per
        // ADR-0007 §5 — they use MSW mocks); only prod-served frontends and
        // local dev need allowance here.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options) // preflight
        // DELETE /v1/sessions/{sessionId} is the GDPR erasure endpoint
        // (ADR-0025 §5). Without this allowance the browser preflight
        // fails and the privacy-notice "Effacer mes données" button
        // surfaces "Failed to fetch" instead of erasing the session.
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        // POST /v1/puzzles/{puzzleId}/hints sends the player's session as a
        // custom header (see openapi.yaml + HttpPuzzleSolver.ts); without
        // this allowance the browser preflight fails and the call never
        // reaches the route.
        allowHeader("X-Session-Id")
        allowHeader("X-Request-Id")
        // W3C Trace Context propagation (PR-F.2 / ADR-0033). The OTel
        // browser SDK's FetchInstrumentation attaches `traceparent` and
        // `tracestate` to outbound fetches matching wordsparrow.io so
        // backend spans nest under the browser fetch in SigNoz. Both
        // headers are non-simple from CORS' POV and must be allowed
        // explicitly — without these entries every POST hint / validate
        // (and indeed every fetch that triggers a preflight) fails with
        // 403 because the browser's preflight checks
        // `Access-Control-Request-Headers` against this list. `baggage`
        // is the W3C Baggage propagation header — not used today by the
        // SDK we ship, but allow-listed proactively because the OTel
        // contrib instrumentations use it interchangeably with the
        // trace headers and a future SDK bump might enable it. CorsTest
        // exercises each so a regression here fails CI before prod.
        allowHeader("traceparent")
        allowHeader("tracestate")
        allowHeader("baggage")

        // Production frontends (Cloudflare Pages serving wordsparrow.io).
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))

        // Local dev — Vite default port 5173 (frontend/package.json `dev`
        // script does not override; frontend/vite.config.ts has no `server`
        // block). If that ever changes, update this entry.
        allowHost("localhost:5173", schemes = listOf("http"))

        // No credentials = no cookies. The API is read-only public for now.
        allowCredentials = false
        maxAgeInSeconds = 86400 // cache preflight for 24h
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    // Must install before CallLogging so callIdMdc binding is in scope for the access log.
    install(CallId) {
        header("X-Request-Id")
        generate {
            java.util.UUID
                .randomUUID()
                .toString()
        }
        verify { it.isNotEmpty() && it.length <= 128 }
        replyToHeader("X-Request-Id")
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("correlation_id")
    }

    install(DefaultHeaders) {
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("X-Frame-Options", "DENY")
        header(HttpHeaders.Server, "WordSparrow")
    }

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6.
        exception<Throwable> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "Erreur interne du serveur",
                    status = HttpStatusCode.InternalServerError.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = Json.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    val version =
        environment.config
            .propertyOrNull("ktor.application.version")
            ?.getString()
            ?: System.getProperty("grid.api.version")
            ?: "unknown"

    val wordRepository = CsvWordRepository.frenchFromClasspath()
    val generatePuzzle = GeneratePuzzleUseCase(wordRepository, defaultPuzzleConstraints())

    // Pick adapters on the live DataSource: production has DATABASE_URL set
    // (Helm chart guarantees it) and gets the durable Postgres path. Local
    // dev / route tests run without a DB and use the in-memory pair so the
    // wire path stays exercisable without spinning up Testcontainers.
    val (puzzleRepository, hintUsageRepository) =
        when (val ds = Database.dataSource()) {
            null -> InMemoryPuzzleRepository() as PuzzleRepository to InMemoryHintUsageRepository() as HintUsageRepository
            else -> PostgresPuzzleRepository(ds) as PuzzleRepository to PostgresHintUsageRepository(ds) as HintUsageRepository
        }
    // Fire-and-forget analytics scope (ADR-0025). Cancelled on app stop so in-flight
    // posts don't outlive the JVM. The adapter falls back to a no-op when MATOMO_URL /
    // MATOMO_SITE_ID / MATOMO_ID_SALT are unset, so dev / staging / pre-Matomo prod work
    // unchanged.
    val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { analyticsScope.cancel() }
    val analyticsEventSink: AnalyticsEventSink = createAnalyticsEventSink(analyticsScope)

    // Clue cooldown (ADR-0031). Off by default; route plumbing not yet wired.
    // Flag retirement: 2026-09-01.
    val cooldownRepository: ClueCooldownRepository? =
        if (System.getenv("GRID_CLUE_COOLDOWN_ENABLED")?.toBooleanStrictOrNull() == true) {
            when (val ds = Database.dataSource()) {
                null -> InMemoryClueCooldownRepository()
                else -> PostgresClueCooldownRepository(ds)
            }
        } else {
            null
        }
    val cooldownMax =
        System.getenv("GRID_CLUE_COOLDOWN_MAX")?.toIntOrNull()
            ?: LoadOrGeneratePuzzleUseCase.DEFAULT_COOLDOWN_MAX

    val loadOrGenerate =
        LoadOrGeneratePuzzleUseCase(
            puzzleRepository = puzzleRepository,
            generatePuzzle = generatePuzzle,
            analyticsEventSink = analyticsEventSink,
            cooldownRepository = cooldownRepository,
            cooldownMax = cooldownMax,
        )
    val revealCellHint =
        RevealCellHintUseCase(puzzleRepository, hintUsageRepository, analyticsEventSink = analyticsEventSink)
    val validatePuzzle = ValidatePuzzleUseCase(puzzleRepository)
    val deleteSession = DeleteSessionUseCase(hintUsageRepository)
    val dailyPuzzleSelector = DailyPuzzleSelector()

    routing {
        health(version)
        puzzles(loadOrGenerate, revealCellHint, validatePuzzle, dailyPuzzleSelector = dailyPuzzleSelector)
        deleteSession(deleteSession)
    }
}

private val analyticsLogger = LoggerFactory.getLogger("com.bliss.grid.api.analytics")

/**
 * Returns a Matomo adapter when all three env vars are configured, otherwise a no-op.
 * Same shape as [com.bliss.game.api.module]'s helper for parity.
 */
private fun createAnalyticsEventSink(scope: CoroutineScope): AnalyticsEventSink {
    val url = System.getenv("MATOMO_URL")?.trim()?.trimEnd('/')
    val siteId = System.getenv("MATOMO_SITE_ID")?.trim()
    val salt = System.getenv("MATOMO_ID_SALT")?.trim()
    return if (!url.isNullOrBlank() && !siteId.isNullOrBlank() && !salt.isNullOrBlank() && salt.length >= 16) {
        analyticsLogger.info("Matomo analytics enabled at {} (site {})", url, siteId)
        MatomoAnalyticsAdapter(
            httpClient = HttpClient(),
            baseUrl = url,
            siteId = siteId,
            idSalt = salt,
            scope = scope,
        )
    } else {
        analyticsLogger.info("Matomo analytics disabled (missing or short MATOMO_URL/MATOMO_SITE_ID/MATOMO_ID_SALT)")
        NoopAnalyticsAdapter()
    }
}
