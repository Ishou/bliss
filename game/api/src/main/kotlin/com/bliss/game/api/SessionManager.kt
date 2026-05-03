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
 * map, leaving the new registration orphaned. The same lock also serialises
 * mutations to [sessionToSessionId] / [sessionIdToSessions] so the
 * "is this player still connected?" predicate ([isSessionConnected]) can be
 * answered atomically against an outgoing `unregister`.
 */
class SessionManager(
    private val log: Logger = LoggerFactory.getLogger(SessionManager::class.java),
    private val json: Json = DEFAULT_JSON,
) {
    private val connections = ConcurrentHashMap<LobbyId, MutableSet<DefaultWebSocketServerSession>>()
    private val perLobbyLock = ConcurrentHashMap<LobbyId, Any>()

    // Ephemeral cursor map per ADR-0018 §9 (Presence). Outer key is the lobby,
    // inner key is the player's sessionId. Lives only in the JVM heap; not
    // persisted, not replicated, not domain. Two-level ConcurrentHashMap is
    // sufficient: every operation is a single put / single remove keyed by
    // sessionId, so per-key atomicity is what the inner map already provides.
    // No additional locking is added — the [perLobbyLock] above guards a
    // *different* invariant (cleanup-vs-register race on `connections` /
    // `sessionIdToSessions`). Stale presence is cleaned up in [unregister]
    // when the per-session entry is dropped, and the entire per-lobby map
    // is dropped when the lobby's connection set empties (the same trigger
    // that prunes [connections]).
    private val presenceState = ConcurrentHashMap<LobbyId, ConcurrentHashMap<String, PresencePosition>>()

    // Per-lobby reverse index: which sessionId is each connected socket
    // identified as? Populated by [bindSession] after a successful joinLobby
    // and cleared by [unregister]. The route uses this to answer "did the
    // disconnect drop the LAST socket for this player, or is another tab of
    // the same browser still attached?" — the multi-tab desync that PR #lobby-state-sync
    // fixes hinged on broadcasting `playerLeft` even when another tab still
    // held the slot.
    private val sessionToSessionId = ConcurrentHashMap<LobbyId, MutableMap<DefaultWebSocketServerSession, String>>()
    private val sessionIdToSessions = ConcurrentHashMap<LobbyId, MutableMap<String, MutableSet<DefaultWebSocketServerSession>>>()

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
     * memory bounded. Returns the sessionId previously bound to [session]
     * via [bindSession] (or `null` if no joinLobby had been processed for
     * this socket yet) so callers can decide whether the disconnect
     * ended the last socket for that player.
     */
    fun unregister(
        lobbyId: LobbyId,
        session: DefaultWebSocketServerSession,
    ): String? =
        synchronized(lockFor(lobbyId)) {
            val boundSessionId = sessionToSessionId[lobbyId]?.remove(session)
            if (boundSessionId != null) {
                val byId = sessionIdToSessions[lobbyId]
                val sockets = byId?.get(boundSessionId)
                if (sockets != null) {
                    sockets.remove(session)
                    if (sockets.isEmpty()) byId.remove(boundSessionId)
                }
                if (byId != null && byId.isEmpty()) sessionIdToSessions.remove(lobbyId)
                if (sessionToSessionId[lobbyId]?.isEmpty() == true) sessionToSessionId.remove(lobbyId)
                // Drop the player's presence entry once the LAST socket for the
                // sessionId is gone — multi-tab close MUST keep presence
                // visible while another tab is still attached.
                if (byId?.get(boundSessionId)?.isNotEmpty() != true) {
                    presenceState[lobbyId]?.remove(boundSessionId)
                }
            }
            val set = connections[lobbyId] ?: return@synchronized boundSessionId
            set.remove(session)
            if (set.isEmpty()) {
                connections.remove(lobbyId)
                perLobbyLock.remove(lobbyId)
                // Last socket for the lobby is gone — drop the whole presence
                // map for this lobby. Covers both "last player left" and the
                // GC-evicted-while-clients-disconnected case (presence is
                // freed at most one disconnect after the lobby is gone).
                presenceState.remove(lobbyId)
            }
            boundSessionId
        }

    /**
     * Records that [session] is the transport for the player identified by
     * [sessionId]. Idempotent: re-binding the same pair is a no-op. A
     * second [bindSession] call on the same [session] with a different
     * [sessionId] replaces the prior binding (the route never does this
     * today, but the behavior is documented for future-proofing).
     */
    fun bindSession(
        lobbyId: LobbyId,
        session: DefaultWebSocketServerSession,
        sessionId: String,
    ) {
        synchronized(lockFor(lobbyId)) {
            val byId = sessionIdToSessions.computeIfAbsent(lobbyId) { ConcurrentHashMap() }
            val bySession = sessionToSessionId.computeIfAbsent(lobbyId) { ConcurrentHashMap() }
            val previous = bySession.put(session, sessionId)
            if (previous != null && previous != sessionId) {
                byId[previous]?.let { sockets ->
                    sockets.remove(session)
                    if (sockets.isEmpty()) byId.remove(previous)
                }
            }
            byId.computeIfAbsent(sessionId) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(session)
        }
    }

    /**
     * True when at least one currently-registered socket in [lobbyId] is
     * bound to [sessionId]. Drives the route's reconnect-grace decision:
     * if a tab closes but another tab of the same browser is still
     * attached, no `playerLeft` broadcast (and no slot release) is owed.
     */
    fun isSessionConnected(
        lobbyId: LobbyId,
        sessionId: String,
    ): Boolean =
        synchronized(lockFor(lobbyId)) {
            sessionIdToSessions[lobbyId]?.get(sessionId)?.isNotEmpty() == true
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

    /**
     * Upsert the cursor position for [sessionId] inside [lobbyId]. A null
     * [position] (or one whose all three fields are null) is treated as
     * "no focus" and REMOVES the entry from the map — that matches the
     * AsyncAPI semantics where an absent player is "not focused", and lets
     * the wire convey both transient un-focus (clear) and player exit
     * (unregister).
     */
    fun recordPresence(
        lobbyId: LobbyId,
        sessionId: String,
        position: PresencePosition?,
    ) {
        if (position == null || (position.row == null && position.column == null && position.direction == null)) {
            presenceState[lobbyId]?.remove(sessionId)
            return
        }
        presenceState
            .computeIfAbsent(lobbyId) { ConcurrentHashMap() }
            .put(sessionId, position)
    }

    /**
     * Snapshot of the current presence map for [lobbyId]. Returns a copy so
     * callers can iterate without observing concurrent mutations. Empty map
     * when no entries exist (or the lobby is unknown to the manager).
     */
    fun getPresence(lobbyId: LobbyId): Map<String, PresencePosition> = presenceState[lobbyId]?.toMap().orEmpty()

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

/**
 * Cursor position carried by [SessionManager.presenceState]. Co-located with
 * the manager because nothing else in the codebase needs it — it is a pure
 * api-layer transport concern (ADR-0018 §9 Presence: ephemeral, not domain).
 * All three fields are nullable mirroring `CellFocusPayload` /
 * `PresenceEntry` on the wire; a position with all three nulls is normalised
 * by [SessionManager.recordPresence] to "remove the entry".
 *
 * `direction` is the wire enum string (`across` | `down`) — kept as a raw
 * String so this file does not import `com.bliss.game.domain.*`.
 */
data class PresencePosition(
    val row: Int?,
    val column: Int?,
    val direction: String?,
)
