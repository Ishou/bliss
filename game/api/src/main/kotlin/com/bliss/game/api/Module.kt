package com.bliss.game.api

import com.bliss.game.api.dto.ProblemDetails
import com.bliss.game.api.routes.health
import com.bliss.game.api.routes.lobbies
import com.bliss.game.api.routes.lobbyWebSocketRoute
import com.bliss.game.application.ports.AnalyticsEventSink
import com.bliss.game.application.usecases.CreateLobbyUseCase
import com.bliss.game.application.usecases.JoinLobbyUseCase
import com.bliss.game.application.usecases.LeaveLobbyUseCase
import com.bliss.game.application.usecases.LobbyGarbageCollector
import com.bliss.game.application.usecases.PresenceAggregator
import com.bliss.game.application.usecases.RenameSelfUseCase
import com.bliss.game.application.usecases.SetGridConfigUseCase
import com.bliss.game.application.usecases.StartGameUseCase
import com.bliss.game.application.usecases.UpdateCellUseCase
import com.bliss.game.infrastructure.HttpPuzzleProvider
import com.bliss.game.infrastructure.HttpWordValidator
import com.bliss.game.infrastructure.InMemoryLobbyRepository
import com.bliss.game.infrastructure.analytics.MatomoAnalyticsAdapter
import com.bliss.game.infrastructure.analytics.NoopAnalyticsAdapter
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wires CORS, content negotiation (JSON), call logging, RFC 7807 errors,
 * the WebSockets plugin, and routes. Mirrors `grid/api`'s Module shape so
 * the two services have the same observability and error envelope.
 *
 * No persistence wiring — game/ is in-memory in v1 (ADR-0018 §3); no
 * Database.start() unlike grid/api. Both REST routes (PR #137) and the
 * WebSocket endpoint (PR #138) consume the same in-memory
 * InMemoryLobbyRepository so a lobby created via REST is visible to a
 * subsequent WebSocket connection on the same process.
 */
fun Application.module() {
    install(CORS) {
        // Browsers block `https://wordsparrow.io` → `https://game.wordsparrow.io`
        // without these headers. Frontend dev server runs on Vite's default 5173.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options) // preflight
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        // X-Request-Id: client-generated correlation id propagated through
        // logs/traces (MANIFESTO Observability). Echoed back via CallId.
        allowHeader("X-Request-Id")

        // Production frontends (Cloudflare Pages serving wordsparrow.io).
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))

        // Local dev — Vite default port 5173 (mirrors grid/api).
        allowHost("localhost:5173", schemes = listOf("http"))

        // No credentials = no cookies. Sessions are sessionId-in-localStorage.
        allowCredentials = false
        maxAgeInSeconds = 86400 // cache preflight for 24h
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                // explicitNulls = true (the kotlinx-serialization default): a
                // required field that is null on the domain side MUST appear on
                // the wire as `null`. Per ADR-0003 §6, absence and `null` are
                // distinct. Most prominently: `Lobby.game` is `null` while
                // WAITING and `GameSession.completedAt` is `null` while
                // IN_PROGRESS — both fields are in the OpenAPI spec's `required`
                // lists and clients distinguish between "not yet" and "missing".
                explicitNulls = true
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

    // game/api is the FIRST WebSocket-using service in this repo (ADR-0018 §3,
    // ADR-0006). The WebSocket route (PR #138) attaches inside `routing { }`
    // below; the plugin install must precede route registration.
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6. IllegalArgumentException is the
        // canonical "client sent something invalid" signal from the application
        // layer's `require(...)` blocks; map it to 400 instead of leaking 500.
        exception<IllegalArgumentException> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "Requête invalide",
                    status = HttpStatusCode.BadRequest.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = Json.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.BadRequest,
            )
        }
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

    // ---- DI for game routes ---------------------------------------------
    // Manual wiring; mirrors grid/api's pattern (no DI framework). v1 is
    // in-memory only (ADR-0018 §3); :game:infrastructure's Postgres adapter
    // will replace InMemoryLobbyRepository when it lands.
    //
    // The repository instance is shared between REST routes (PR #137) and
    // the WebSocket route (PR #138) so a lobby created via POST /v1/lobbies
    // is visible to a subsequent WebSocket connection on the same process.
    val lobbyRepository = InMemoryLobbyRepository()
    // Local-dev default: grid-api on the host's loopback (paired with
    // grid/api's DEFAULT_PORT=7777). Prod chart pins GRID_BASE_URL
    // explicitly via the deployment env block, so the cluster routes
    // through the in-cluster Kubernetes Service DNS regardless of the
    // local default. Mirrors the PORT pattern in `Main.kt`.
    val gridBaseUrl = System.getenv("GRID_BASE_URL") ?: "http://localhost:7777"
    val sharedHttpClient = HttpClient()
    val puzzleProvider = HttpPuzzleProvider(sharedHttpClient, gridBaseUrl)
    val wordValidator = HttpWordValidator(sharedHttpClient, gridBaseUrl)

    // Fire-and-forget analytics scope (ADR-0025). Cancelled on app stop so in-flight
    // posts don't outlive the JVM. Adapter falls back to a no-op when the three
    // MATOMO_* env vars are unset, so dev / pre-Matomo prod work unchanged.
    val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { analyticsScope.cancel() }
    val analyticsEventSink: AnalyticsEventSink = createAnalyticsEventSink(analyticsScope)

    val useCases =
        LobbyUseCases(
            createLobby = CreateLobbyUseCase(lobbyRepository, SystemClock, analyticsEventSink = analyticsEventSink),
            joinLobby = JoinLobbyUseCase(lobbyRepository, SystemClock, analyticsEventSink = analyticsEventSink),
            renameSelf = RenameSelfUseCase(lobbyRepository, SystemClock, analyticsEventSink = analyticsEventSink),
            setGridConfig = SetGridConfigUseCase(lobbyRepository, SystemClock),
            startGame = StartGameUseCase(lobbyRepository, puzzleProvider, SystemClock, analyticsEventSink = analyticsEventSink),
            updateCell = UpdateCellUseCase(lobbyRepository, SystemClock, wordValidator, analyticsEventSink = analyticsEventSink),
            leaveLobby = LeaveLobbyUseCase(lobbyRepository, SystemClock, analyticsEventSink = analyticsEventSink),
        )
    val sessionManager = SessionManager()

    // Lobby garbage collector — evicts WAITING lobbies idle for >30 minutes (ADR-0018 §3
    // already promises this in `game/api/openapi.yaml`'s 404 description). Companion to the
    // per-session idempotency in CreateLobbyUseCase: idempotency stops the click-to-spam
    // path at the source; the GC mops up tab-close / network-drop / crash residue.
    //
    // 30 minutes is the right knob for a casual game: long enough that a player who tabs
    // away to reply to a message can come back, short enough that abandoned lobbies do not
    // accumulate over a play session. The 5-minute sweep cadence balances responsiveness
    // against scan cost (O(n) over the in-memory map).
    val gc =
        LobbyGarbageCollector(
            repo = lobbyRepository,
            clock = SystemClock,
            idleTtl = Duration.ofMinutes(30),
            sweepInterval = Duration.ofMinutes(5),
        )
    val gcJob = gc.run(this)
    monitor.subscribe(ApplicationStopped) { gcJob.cancel() }

    // 1s tick: coarser than the ~1.5s typing-edge gap; sub-second jitter has no UX impact.
    val presenceBroadcaster = WebSocketPresenceBroadcaster(sessionManager)
    val presenceAggregator =
        PresenceAggregator(
            clock = SystemClock,
            broadcaster = presenceBroadcaster,
        )
    val presenceJob = presenceAggregator.run(this, tickInterval = Duration.ofSeconds(1))
    monitor.subscribe(ApplicationStopped) { presenceJob.cancel() }

    routing {
        health(APP_VERSION)
        lobbies(createLobby = useCases.createLobby, repo = lobbyRepository, sessionManager = sessionManager)
        lobbyWebSocketRoute(sessionManager, useCases, lobbyRepository, presenceAggregator)
    }
}

private val analyticsLogger = LoggerFactory.getLogger("com.bliss.game.api.analytics")

/**
 * Returns a Matomo adapter when all three env vars are configured, otherwise a no-op.
 * Mirrors the equivalent helper in `:grid:api`'s Module so both contexts behave alike.
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
