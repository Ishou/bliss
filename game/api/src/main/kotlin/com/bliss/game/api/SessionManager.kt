package com.bliss.game.api

import com.bliss.game.api.dto.ServerToClientFrame
import com.bliss.game.domain.LobbyId
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of WebSocket sessions keyed by lobby. Single-replica
 * deployment per ADR-0018 §3 keeps this in the JVM heap — no Redis / pub-sub
 * needed for v1.
 *
 * Concurrency model: the outer [connections] map uses a [ConcurrentHashMap]
 * for lock-free reads of the per-lobby session sets. Each lobby's session
 * set is itself a concurrent set (`Collections.newSetFromMap(ConcurrentHashMap)`)
 * so iteration during [broadcast] does not require any external lock. The
 * per-lobby [perLobbyLock] guards the *cleanup race* between the last
 * `unregister` and a concurrent `register` — without it, a registering
 * coroutine could observe an empty set that is then removed from the outer
 * map, leaving the new registration orphaned.
 */
class SessionManager(
    private val log: Logger = LoggerFactory.getLogger(SessionManager::class.java),
    private val json: Json = DEFAULT_JSON,
) {
    private val connections = ConcurrentHashMap<LobbyId, MutableSet<DefaultWebSocketServerSession>>()
    private val perLobbyLock = ConcurrentHashMap<LobbyId, Any>()

    private fun lockFor(lobbyId: LobbyId): Any = perLobbyLock.computeIfAbsent(lobbyId) { Any() }

    /**
     * Adds [session] to the lobby's session set. Returns `true` if the session
     * was a new entry, `false` if it was already registered (idempotent).
     */
    fun register(
        lobbyId: LobbyId,
        session: DefaultWebSocketServerSession,
    ): Boolean =
        synchronized(lockFor(lobbyId)) {
            val set =
                connections.computeIfAbsent(lobbyId) {
                    Collections.newSetFromMap(ConcurrentHashMap())
                }
            set.add(session)
        }

    /**
     * Removes [session] from the lobby's session set. If the lobby's set
     * becomes empty, the lobby entry is removed from both maps to keep
     * memory bounded.
     */
    fun unregister(
        lobbyId: LobbyId,
        session: DefaultWebSocketServerSession,
    ) {
        synchronized(lockFor(lobbyId)) {
            val set = connections[lobbyId] ?: return
            set.remove(session)
            if (set.isEmpty()) {
                connections.remove(lobbyId)
                perLobbyLock.remove(lobbyId)
            }
        }
    }

    /**
     * Sends [frame] to every session currently registered for [lobbyId].
     * Per-session send failures are caught and logged so a single flaky
     * client cannot block the broadcast to the rest of the lobby.
     *
     * The set is iterated via a snapshot copy (`toList`) so concurrent
     * register / unregister calls during the broadcast are safe.
     */
    suspend fun broadcast(
        lobbyId: LobbyId,
        frame: ServerToClientFrame,
    ) {
        val sessions = connections[lobbyId]?.toList() ?: return
        val payload = json.encodeToString(ServerToClientFrame.serializer(), frame)
        for (session in sessions) {
            try {
                session.send(payload)
            } catch (cause: Throwable) {
                log.warn(
                    "ws.broadcast.send_failed lobbyId={} frameType={} cause={}",
                    lobbyId.value,
                    frame::class.simpleName,
                    cause.message,
                )
            }
        }
    }

    fun connectedCount(lobbyId: LobbyId): Int = connections[lobbyId]?.size ?: 0

    companion object {
        internal val DEFAULT_JSON: Json =
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                explicitNulls = true
                classDiscriminator = "type"
            }
    }
}
