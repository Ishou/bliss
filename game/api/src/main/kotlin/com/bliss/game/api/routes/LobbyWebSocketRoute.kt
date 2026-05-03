package com.bliss.game.api.routes

import com.bliss.game.api.LobbyUseCases
import com.bliss.game.api.SessionManager
import com.bliss.game.api.dto.ClientToServerFrame
import com.bliss.game.api.dto.ServerToClientFrame
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.usecases.UseCaseOutcome
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.bliss.game.api.routes.LobbyWebSocketRoute")

private val ROUTE_JSON: Json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = true
        classDiscriminator = "type"
    }

private val SESSION_ID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

/** ADR-0018 §5: a closed socket reserves the player's slot for this long before it's freed. */
internal val DEFAULT_RECONNECT_GRACE: Duration = 30.seconds

/**
 * `/v1/lobbies/{lobbyId}/ws` — the real-time half of the multiplayer feature
 * (ADR-0018, AsyncAPI catalog in `game/api/asyncapi.yaml`).
 *
 * Lifecycle per connection:
 *  1. Validate `{lobbyId}` (path param) and the optional `?sessionId=` query
 *     param (UUID v7 shape). Reject malformed inputs with an error frame and
 *     a close before the session is registered.
 *  2. Verify the lobby exists. Otherwise: error frame + close.
 *  3. Register the session, send a `lobbyState` snapshot to this socket only.
 *  4. Read text frames in a loop. Each frame is deserialized to a
 *     [ClientToServerFrame] and dispatched to the matching use case.
 *     [UseCaseOutcome.Success] events are mapped to wire frames and
 *     broadcast; [UseCaseOutcome.Failure] sends a single error frame to
 *     the originator only.
 *  5. On close (any reason), unregister and check whether ANY other socket
 *     for the same `sessionId` is still attached (multi-tab / quick reload).
 *     If yes — no broadcast: the slot is still held. If no — schedule the
 *     ADR-0018 §5 30s reconnect grace; once it elapses with the player still
 *     absent, dispatch [LobbyUseCases.leaveLobby] (which removes the player
 *     from the authoritative lobby state and emits a `playerLeft` event)
 *     and broadcast the resulting frames. Earlier, the route fired a bare
 *     `playerLeft` immediately on every disconnect — which (a) ignored the
 *     30 s reconnect window, (b) did not free the slot server-side
 *     (the `Lobby` aggregate kept the player), and (c) made every other
 *     tab of the same browser look like it had vanished. Symptom captured
 *     in the field: "mobile saw 1 joueur, web saw 3 joueurs" after a
 *     same-browser tab close.
 */
fun Route.lobbyWebSocketRoute(
    sessionManager: SessionManager,
    useCases: LobbyUseCases,
    repo: LobbyRepository,
    backgroundScope: CoroutineScope = defaultBackgroundScope,
    reconnectGrace: Duration = DEFAULT_RECONNECT_GRACE,
) {
    webSocket("/v1/lobbies/{lobbyId}/ws") {
        val lobbyId = parseLobbyIdOrClose() ?: return@webSocket
        val querySessionId = parseQuerySessionIdOrClose() ?: return@webSocket

        val current = repo.findById(lobbyId)
        if (current == null) {
            sendError("Lobby not found", "No lobby with id ${lobbyId.value} exists.", status = 404)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "lobby not found"))
            return@webSocket
        }

        sessionManager.register(lobbyId, this)
        // Initial snapshot to this socket only — bootstrap signal for the UI.
        send(encode(current.toLobbyStateFrame()))

        // Track the player's identity once they joinLobby so we can synthesize
        // a playerLeft broadcast on disconnect even if they never sent leaveLobby.
        var memberSessionId: String? = querySessionId.takeIf { it.isNotEmpty() }
        if (memberSessionId != null) {
            // Bind the query-string session id eagerly so a disconnect before
            // the joinLobby frame still benefits from the multi-tab dedupe. The
            // canonical bind happens inside [dispatchJoin] once joinLobby succeeds.
            sessionManager.bindSession(lobbyId, this, memberSessionId)
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = parseFrameOrError(frame.readText()) ?: continue
                memberSessionId =
                    handleFrame(parsed, lobbyId, useCases, sessionManager, this, memberSessionId)
            }
        } finally {
            val boundSessionId = sessionManager.unregister(lobbyId, this) ?: memberSessionId
            if (boundSessionId != null) {
                scheduleReconnectGrace(
                    backgroundScope = backgroundScope,
                    sessionManager = sessionManager,
                    useCases = useCases,
                    lobbyId = lobbyId,
                    sessionId = boundSessionId,
                    grace = reconnectGrace,
                )
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.parseLobbyIdOrClose(): LobbyId? {
    val raw = call.parameters["lobbyId"]
    if (raw == null) {
        sendError("Missing lobbyId", "Path parameter 'lobbyId' is required.")
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing lobbyId"))
        return null
    }
    return try {
        LobbyId(raw)
    } catch (cause: IllegalArgumentException) {
        sendError("Invalid lobbyId", cause.message ?: "lobbyId failed validation")
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid lobbyId"))
        null
    }
}

/** Optional ?sessionId=… query param — defensive shape check, the canonical id is in joinLobby. */
private suspend fun DefaultWebSocketServerSession.parseQuerySessionIdOrClose(): String? {
    val raw = call.request.queryParameters["sessionId"] ?: return ""
    if (!SESSION_ID_REGEX.matches(raw)) {
        sendError("Invalid sessionId", "Query parameter 'sessionId' must be a UUID v7.")
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid sessionId"))
        return null
    }
    return raw
}

private suspend fun DefaultWebSocketServerSession.parseFrameOrError(text: String): ClientToServerFrame? =
    try {
        ROUTE_JSON.decodeFromString(ClientToServerFrame.serializer(), text)
    } catch (cause: SerializationException) {
        sendError("Malformed frame", cause.message ?: "frame failed to deserialize")
        null
    } catch (cause: IllegalArgumentException) {
        sendError("Invalid frame", cause.message ?: "frame failed validation")
        null
    }

private suspend fun DefaultWebSocketServerSession.handleFrame(
    parsed: ClientToServerFrame,
    lobbyId: LobbyId,
    useCases: LobbyUseCases,
    sessionManager: SessionManager,
    session: DefaultWebSocketServerSession,
    memberSessionId: String?,
): String? {
    val effectiveId =
        if (memberSessionId.isNullOrEmpty()) null else memberSessionId
    return when (parsed) {
        is ClientToServerFrame.JoinLobby ->
            dispatchJoin(parsed, lobbyId, useCases, sessionManager, session) ?: effectiveId
        is ClientToServerFrame.RenameSelf -> {
            val sid =
                effectiveId ?: run {
                    sendNotJoined()
                    return memberSessionId
                }
            dispatch(lobbyId, sessionManager) {
                useCases.renameSelf(lobbyId, SessionId(sid), Pseudonym.of(parsed.newPseudonym))
            }
            memberSessionId
        }
        is ClientToServerFrame.SetGridConfig -> {
            val sid =
                effectiveId ?: run {
                    sendNotJoined()
                    return memberSessionId
                }
            val outcome =
                useCases.setGridConfig(
                    lobbyId,
                    SessionId(sid),
                    GridConfig(parsed.width, parsed.height),
                )
            handleOutcome(outcome, lobbyId, sessionManager)
            memberSessionId
        }
        ClientToServerFrame.StartGame -> {
            val sid =
                effectiveId ?: run {
                    sendNotJoined()
                    return memberSessionId
                }
            dispatch(lobbyId, sessionManager) {
                useCases.startGame(lobbyId, SessionId(sid))
            }
            memberSessionId
        }
        is ClientToServerFrame.CellUpdate -> {
            val sid =
                effectiveId ?: run {
                    sendNotJoined()
                    return memberSessionId
                }
            val letter =
                try {
                    parsed.letter?.let { Letter(it.single()) }
                } catch (_: Exception) {
                    sendError("Invalid letter", "letter must be a single uppercase A-Z character or null")
                    return memberSessionId
                }
            dispatch(lobbyId, sessionManager) {
                useCases.updateCell(
                    lobbyId,
                    SessionId(sid),
                    Position(parsed.row, parsed.column),
                    letter,
                )
            }
            memberSessionId
        }
        ClientToServerFrame.LeaveLobby -> {
            val sid =
                effectiveId ?: run {
                    sendNotJoined()
                    return memberSessionId
                }
            dispatch(lobbyId, sessionManager) {
                useCases.leaveLobby(lobbyId, SessionId(sid))
            }
            // Returning null here only prevents a grace timer when the session
            // was never bound (unregister returns null AND memberSessionId is
            // null → finally skips scheduleReconnectGrace). For a player who
            // completed joinLobby, unregister always returns the bound sessionId
            // so a grace coroutine still fires; the second leaveLobby returns
            // Failure(PlayerNotInLobby), which scheduleReconnectGrace silently
            // swallows — no double broadcast.
            null
        }
    }
}

private suspend fun DefaultWebSocketServerSession.dispatchJoin(
    parsed: ClientToServerFrame.JoinLobby,
    lobbyId: LobbyId,
    useCases: LobbyUseCases,
    sessionManager: SessionManager,
    session: DefaultWebSocketServerSession,
): String? {
    val sid =
        try {
            SessionId(parsed.sessionId)
        } catch (cause: IllegalArgumentException) {
            sendError("Invalid sessionId", cause.message ?: "sessionId failed validation")
            return null
        }
    val pseudo =
        try {
            Pseudonym.of(parsed.pseudonym)
        } catch (cause: IllegalArgumentException) {
            sendError("Invalid pseudonym", cause.message ?: "pseudonym failed validation")
            return null
        }
    val outcome = useCases.joinLobby(lobbyId, sid, pseudo)
    handleOutcome(outcome, lobbyId, sessionManager)
    return if (outcome is UseCaseOutcome.Success) {
        // Bind the socket to the player's sessionId so a subsequent
        // disconnect can answer "is another tab of this browser still
        // here?" — a multi-tab close must NOT broadcast `playerLeft`
        // when the player is still represented by another live socket.
        sessionManager.bindSession(lobbyId, session, sid.value)
        sid.value
    } else {
        null
    }
}

private suspend fun <T> DefaultWebSocketServerSession.dispatch(
    lobbyId: LobbyId,
    sessionManager: SessionManager,
    block: suspend () -> UseCaseOutcome<T>,
) {
    val outcome = block()
    handleOutcome(outcome, lobbyId, sessionManager)
}

private suspend fun <T> DefaultWebSocketServerSession.handleOutcome(
    outcome: UseCaseOutcome<T>,
    lobbyId: LobbyId,
    sessionManager: SessionManager,
) {
    when (outcome) {
        is UseCaseOutcome.Success -> {
            for (event in outcome.result.events) {
                event.toFrameOrNull()?.let { sessionManager.broadcast(lobbyId, it) }
            }
            // GridConfigChanged has no dedicated wire frame — re-broadcast the
            // full snapshot so all clients converge on the new dimensions
            // (per the wire-mapping note in LobbyEvent.kt).
            val needsSnapshot = outcome.result.events.any { it is LobbyEvent.GridConfigChanged }
            if (needsSnapshot) {
                (outcome.result.value as? Lobby)?.let { lobby ->
                    sessionManager.broadcast(lobbyId, lobby.toLobbyStateFrame())
                }
            }
        }
        is UseCaseOutcome.Failure ->
            send(encode(outcome.error.toErrorFrame()))
    }
}

private suspend fun DefaultWebSocketServerSession.sendNotJoined() {
    send(
        encode(
            protocolErrorFrame(
                title = "Not joined",
                detail = "Send a 'joinLobby' frame before issuing other operations.",
                status = 409,
            ),
        ),
    )
}

private suspend fun DefaultWebSocketServerSession.sendError(
    title: String,
    detail: String,
    status: Int = 400,
) {
    send(encode(protocolErrorFrame(title, detail, status)))
}

/**
 * Schedules the ADR-0018 §5 reconnect-grace check. If, after [grace] elapses,
 * no socket in [lobbyId] is bound to [sessionId], the player is removed from
 * the lobby aggregate via [LobbyUseCases.leaveLobby] and the resulting events
 * (`playerLeft`, possibly `lobbyClosed`) are broadcast to the survivors. If a
 * reconnect lands inside the window the timer fires but observes a still-bound
 * sessionId and exits silently.
 *
 * Eager short-circuit: when another socket is already attached at unregister
 * time (the common multi-tab close case) we skip the launch entirely so the
 * grace coroutine doesn't even spin up.
 */
private fun scheduleReconnectGrace(
    backgroundScope: CoroutineScope,
    sessionManager: SessionManager,
    useCases: LobbyUseCases,
    lobbyId: LobbyId,
    sessionId: String,
    grace: Duration,
) {
    if (sessionManager.isSessionConnected(lobbyId, sessionId)) {
        // Another tab of the same browser is still attached — the slot
        // is held; nothing to broadcast and nothing to schedule.
        return
    }
    backgroundScope.launch {
        if (grace > Duration.ZERO) delay(grace)
        if (sessionManager.isSessionConnected(lobbyId, sessionId)) {
            // Reconnected inside the window — slot is held by the new socket.
            return@launch
        }
        try {
            val outcome = useCases.leaveLobby(lobbyId, SessionId(sessionId))
            if (outcome is UseCaseOutcome.Success) {
                for (event in outcome.result.events) {
                    event.toFrameOrNull()?.let { sessionManager.broadcast(lobbyId, it) }
                }
            }
            // A Failure (e.g. PlayerNotInLobby — already removed by another path,
            // or LobbyNotFound — already deleted) is the no-op outcome; nothing
            // to broadcast.
        } catch (cause: Throwable) {
            log.warn(
                "ws.reconnect_grace.leave_failed lobbyId={} sessionId={} cause={}",
                lobbyId.value,
                sessionId,
                cause.message,
            )
        }
    }
}

private fun encode(frame: ServerToClientFrame): String = ROUTE_JSON.encodeToString(ServerToClientFrame.serializer(), frame)

/**
 * Default scope for the reconnect-grace timer. A [SupervisorJob]-style scope
 * wired in production would be cleaner, but the route is wired once at
 * module install and never torn down inside a running JVM — so a long-lived
 * default is acceptable. Tests inject their own scope.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private val defaultBackgroundScope: CoroutineScope =
    CoroutineScope(GlobalScope.coroutineContext + Dispatchers.Default)
