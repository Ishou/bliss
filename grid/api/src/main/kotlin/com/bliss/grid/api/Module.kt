package com.bliss.grid.api

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.infrastructure.Database
import com.bliss.grid.api.routes.deleteSession
import com.bliss.grid.api.routes.health
import com.bliss.grid.api.routes.puzzles
import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.application.auth.CookieVerifier
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.DeleteSessionUseCase
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.HintUsageRepository
import com.bliss.grid.application.puzzle.HintWriteCoordinator
import com.bliss.grid.application.puzzle.ListDailyPuzzlesUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.infrastructure.analytics.MatomoAnalyticsAdapter
import com.bliss.grid.infrastructure.analytics.NoopAnalyticsAdapter
import com.bliss.grid.infrastructure.auth.HttpCookieVerifier
import com.bliss.grid.infrastructure.events.MaxDeliveriesDlqRepublisher
import com.bliss.grid.infrastructure.events.NatsConnectionFactory
import com.bliss.grid.infrastructure.events.UserEventSubscribers
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import com.bliss.grid.infrastructure.persistence.InMemoryClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintWriteCoordinator
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import com.bliss.grid.infrastructure.persistence.PostgresClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.PostgresHintUsageRepository
import com.bliss.grid.infrastructure.persistence.PostgresHintWriteCoordinator
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
        // POST /v1/puzzles/{id}/hints (PuzzleRoute.kt:226) and
        // POST /v1/puzzles/{id}/validate (PuzzleRoute.kt:298). POST is
        // technically a CORS-simple method, but Ktor's plugin only emits
        // it in `Access-Control-Allow-Methods` when explicit — mirrors
        // game/api Module.kt:70 so the configs read identically.
        allowMethod(HttpMethod.Post)
        // DELETE /v1/sessions/{sessionId} is the GDPR erasure endpoint
        // (ADR-0025 §5). Methods stay explicit (vs. the wildcard used
        // for headers in ADR-0034) because the set is small and the
        // security trade-off differs — wildcard methods would silently
        // allow PUT/PATCH on what is a read-leaning API.
        allowMethod(HttpMethod.Delete)

        // Headers: wildcard allow per ADR-0034. The previous explicit
        // allowlist accumulated three production incidents in two
        // months — DELETE for GDPR (PR #259), X-Request-Id for
        // correlation IDs (PR #267), traceparent / tracestate for the
        // OTel SDK (PR-F.2) — each one a contributor adding an outbound
        // header that middleware silently attaches to every fetch and
        // forgetting the CORS plugin needs to be told. The defense-in-
        // depth `allowHeader` gave us was small (no credentials, public
        // read API, backends ignore unknown headers), the operational
        // tax was large. Origin allowlist + per-IP rate limit at ingress
        // remain the actual security perimeter. ADR-0034 has the full
        // trade-off + the conditions under which this would be revisited
        // (most prominently: gaining auth would force a return to the
        // explicit list because credentialed CORS is incompatible with
        // wildcard headers).
        allowHeaders { true }

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

        // POST /v1/puzzles/{id}/hints + /validate send `Content-Type:
        // application/json`, which the CORS spec classifies as non-simple.
        // Ktor's CORS plugin defaults to rejecting actual (non-preflight)
        // requests carrying a non-simple Content-Type with 403 + no
        // `Access-Control-Allow-Origin`, even when both Origin and Method
        // passed the preflight. The browser surfaces this as `blocked by
        // CORS policy: No 'Access-Control-Allow-Origin' header is present
        // on the requested resource.` — same gotcha game/api documents at
        // Module.kt:93-102. CorsTest covers the regression.
        allowNonSimpleContentTypes = true
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
    val (puzzleRepository, hintUsageRepository, hintWriteCoordinator) =
        when (val ds = Database.dataSource()) {
            null ->
                Triple(
                    InMemoryPuzzleRepository() as PuzzleRepository,
                    InMemoryHintUsageRepository() as HintUsageRepository,
                    InMemoryHintWriteCoordinator() as HintWriteCoordinator,
                )
            else ->
                Triple(
                    PostgresPuzzleRepository(ds) as PuzzleRepository,
                    PostgresHintUsageRepository(ds) as HintUsageRepository,
                    PostgresHintWriteCoordinator(ds) as HintWriteCoordinator,
                )
        }
    // Fire-and-forget analytics scope (ADR-0025). Cancelled on app stop so in-flight
    // posts don't outlive the JVM. The adapter falls back to a no-op when MATOMO_URL /
    // MATOMO_SITE_ID / MATOMO_ID_SALT are unset, so dev / staging / pre-Matomo prod work
    // unchanged.
    val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { analyticsScope.cancel() }
    val analyticsEventSink: AnalyticsEventSink = createAnalyticsEventSink(analyticsScope)

    val identityApiBaseUrl =
        environment.config
            .propertyOrNull("identity.apiBaseUrl")
            ?.getString()
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("IDENTITY_API_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: "http://wordsparrow-identity-api.wordsparrow:8082"

    val verifierHttpClient = HttpClient()
    monitor.subscribe(ApplicationStopped) { verifierHttpClient.close() }
    val cookieVerifier: CookieVerifier = HttpCookieVerifier(verifierHttpClient, identityApiBaseUrl)

    // NATS JetStream subscribers (ADR-0049); gated on NATS_URL so the service boots without a NATS server.
    val moduleLog = LoggerFactory.getLogger("com.bliss.grid.api.Module")
    val natsUrl = System.getenv("NATS_URL")?.takeIf { it.isNotBlank() }
    if (natsUrl != null) {
        runCatching { NatsConnectionFactory(natsUrl).connect() }
            .onSuccess { (natsConnection, jetStream) ->
                val userEventSubscribers = UserEventSubscribers(jetStream, hintUsageRepository)
                userEventSubscribers.start()
                // DLQ republisher: routes messages that exhaust MaxDeliver to the DLQ stream (ADR-0049).
                val dlqRepublisher =
                    MaxDeliveriesDlqRepublisher(
                        connection = natsConnection,
                        jetStreamManagement = natsConnection.jetStreamManagement(),
                        streamName = MaxDeliveriesDlqRepublisher.USER_EVENTS_STREAM,
                        consumerNames = listOf("grid-user-deleted"),
                    )
                dlqRepublisher.start()
                monitor.subscribe(ApplicationStopped) {
                    dlqRepublisher.close()
                    userEventSubscribers.close()
                    natsConnection.close()
                }
                moduleLog.info("grid-api NATS subscribers started against {}", natsUrl)
            }.onFailure { cause ->
                moduleLog.warn("grid-api NATS connection failed at {}; continuing without subscribers", natsUrl, cause)
            }
    } else {
        moduleLog.info("grid-api NATS subscribers disabled (NATS_URL unset)")
    }

    // Clue cooldown (ADR-0031). On by default — set GRID_CLUE_COOLDOWN_ENABLED=false
    // to disable the wiring at the kill-switch level (use case sees null repo,
    // route still parses X-Session-Id but the cooldown read/write path is
    // bypassed). Flag retirement: 2026-09-01.
    val cooldownRepository: ClueCooldownRepository? =
        if (System.getenv("GRID_CLUE_COOLDOWN_ENABLED")?.toBooleanStrictOrNull() != false) {
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
    val deleteSession = DeleteSessionUseCase(cooldownRepository)
    val dailyPuzzleSelector = DailyPuzzleSelector()
    val listDailyPuzzles =
        ListDailyPuzzlesUseCase(
            puzzleRepository = puzzleRepository,
            dailyPuzzleSelector = dailyPuzzleSelector,
        )

    routing {
        health(version)
        puzzles(
            loadOrGenerate,
            revealCellHint,
            validatePuzzle,
            puzzleRepository = puzzleRepository,
            hintUsageRepository = hintUsageRepository,
            hintWriteCoordinator = hintWriteCoordinator,
            cookieVerifier = cookieVerifier,
            listDailyPuzzles = listDailyPuzzles,
            dailyPuzzleSelector = dailyPuzzleSelector,
        )
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
