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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
 *  5. On close (any reason), unregister and broadcast a `playerLeft` frame
 *     to remaining members. We intentionally do NOT call the
 *     LeaveLobbyUseCase here — disconnect is treated as a *temporary* drop
 *     within the ~30s reconnect window (per AsyncAPI). The explicit
 *     `leaveLobby` frame is the way to vacate a slot for good. The
 *     `playerLeft` broadcast lets observers reflect the connection state
 *     immediately.
 */
fun Route.lobbyWebSocketRoute(
    sessionManager: SessionManager,
    useCases: LobbyUseCases,
    repo: LobbyRepository,
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

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = parseFrameOrError(frame.readText()) ?: continue
                memberSessionId = handleFrame(parsed, lobbyId, useCases, sessionManager, memberSessionId)
            }
        } finally {
            sessionManager.unregister(lobbyId, this)
            memberSessionId?.let { sid ->
                runCatchingBroadcast(sessionManager, lobbyId, ServerToClientFrame.PlayerLeft(sessionId = sid))
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
    memberSessionId: String?,
): String? {
    val effectiveId =
        if (memberSessionId.isNullOrEmpty()) null else memberSessionId
    return when (parsed) {
        is ClientToServerFrame.JoinLobby ->
            dispatchJoin(parsed, lobbyId, useCases, sessionManager) ?: effectiveId
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
            // Clear so finally{} does not re-broadcast a duplicate playerLeft.
            null
        }
    }
}

private suspend fun DefaultWebSocketServerSession.dispatchJoin(
    parsed: ClientToServerFrame.JoinLobby,
    lobbyId: LobbyId,
    useCases: LobbyUseCases,
    sessionManager: SessionManager,
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
    return if (outcome is UseCaseOutcome.Success) sid.value else null
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

private suspend fun runCatchingBroadcast(
    sessionManager: SessionManager,
    lobbyId: LobbyId,
    frame: ServerToClientFrame,
) {
    try {
        sessionManager.broadcast(lobbyId, frame)
    } catch (cause: Throwable) {
        log.warn("ws.disconnect.broadcast_failed lobbyId={} cause={}", lobbyId.value, cause.message)
    }
}

private fun encode(frame: ServerToClientFrame): String = ROUTE_JSON.encodeToString(ServerToClientFrame.serializer(), frame)
