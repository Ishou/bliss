package com.bliss.game.application.usecases

import com.bliss.game.application.ports.Clock
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.PresenceBroadcaster
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import com.bliss.game.domain.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-session activity state for the multiplayer-highlights presence layer:
 *
 *  - rising/falling [LobbyEvent.Typing] edges around bursts of keystrokes,
 *  - rising/falling [LobbyEvent.Idle] edges around the [idleThreshold] inactivity window,
 *  - [LobbyEvent.ConnectionLost] on disconnect (slot held during the grace window),
 *  - [LobbyEvent.CursorBumped] when a validated answer relocated a peer's cursor.
 *
 * Edge events are pushed through [PresenceBroadcaster] rather than returned from a use case
 * call, because timer-driven transitions (typing trailing edge, idle threshold crossing) fire
 * outside any single inbound frame's scope. Inbound frames (`cellUpdate`, `cellFocus`) call
 * [recordKeystroke] / [recordFocus]; a periodic [tickOnce] call walks the session table and
 * fires whichever trailing edges are due.
 *
 * Per-session state is held in a [ConcurrentHashMap] — single-replica posture per ADR-0018 §3
 * matches the existing `SessionManager`. A multi-replica future would replace this with a
 * shared-state adapter behind the same port.
 *
 * Thresholds are constructor parameters so tests can pin them without monkey-patching constants.
 */
class PresenceAggregator(
    private val clock: Clock,
    private val broadcaster: PresenceBroadcaster,
    /** Trailing-edge delay for [LobbyEvent.Typing] (`typing=false` after this idle gap). */
    val typingTrailingEdge: Duration = Duration.ofMillis(1500),
    /** Activity gap that crosses into [LobbyEvent.Idle] (`idle=true`). */
    val idleThreshold: Duration = Duration.ofSeconds(30),
    /**
     * Grace window after [recordDisconnect] before the session is forgotten by [tickOnce].
     * The aggregator itself does NOT emit `playerLeft`; converting a still-disconnected session
     * into [LobbyEvent.PlayerLeft] is the responsibility of the use case that owns lobby
     * membership (it can read [trackedSessionCount] for telemetry, but the source of truth for
     * "the slot is gone" is [LobbyRepository], not this aggregator).
     */
    val disconnectGrace: Duration = Duration.ofSeconds(30),
    private val log: Logger = LoggerFactory.getLogger(PresenceAggregator::class.java),
) {
    /**
     * Mutable per-session state. Held inside the map so [tickOnce] can iterate without
     * snapshotting; concurrent mutation from `record*` calls is safe via the
     * [ConcurrentHashMap.compute] entrypoints below.
     */
    private data class SessionState(
        val lobbyId: LobbyId,
        var lastKeystrokeAt: Instant?,
        var lastActivityAt: Instant,
        var disconnectedAt: Instant?,
        var typing: Boolean,
        var idle: Boolean,
    )

    private val sessions = ConcurrentHashMap<SessionId, SessionState>()

    /**
     * Visible-for-test count of tracked sessions. Production code never reads this — it is here
     * so timer-expiration assertions can confirm the aggregator forgot a session after the grace
     * window without exposing the [SessionState] type.
     */
    fun trackedSessionCount(): Int = sessions.size

    /**
     * Records a keystroke for [sessionId] in [lobbyId]. Always resets the last-activity stamp,
     * cancels any pending disconnect grace, and:
     *  - fires [LobbyEvent.Typing] (`typing=true`) on the rising edge,
     *  - fires [LobbyEvent.Idle] (`idle=false`) when the session was previously idle.
     */
    suspend fun recordKeystroke(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ) {
        val now = clock.now()
        val (typingRose, idleFell) =
            transition(sessionId) { prior ->
                val nextState =
                    (prior ?: blankState(lobbyId, now)).also { s ->
                        s.lastKeystrokeAt = now
                        s.lastActivityAt = now
                        s.disconnectedAt = null
                    }
                val typingRose = !nextState.typing
                if (typingRose) nextState.typing = true
                val idleFell = nextState.idle
                if (idleFell) nextState.idle = false
                nextState to (typingRose to idleFell)
            }
        if (typingRose) broadcaster.broadcast(lobbyId, LobbyEvent.Typing(sessionId, typing = true))
        if (idleFell) broadcaster.broadcast(lobbyId, LobbyEvent.Idle(sessionId, idle = false))
    }

    /**
     * Records a presence-focus heartbeat (the AsyncAPI `cellFocus` frame). Resets the activity
     * stamp without affecting the typing edge — focus alone is not "typing", but it is activity
     * for the idle timer. Cancels any pending disconnect grace.
     */
    suspend fun recordFocus(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ) {
        val now = clock.now()
        val (_, idleFell) =
            transition(sessionId) { prior ->
                val nextState =
                    (prior ?: blankState(lobbyId, now)).also { s ->
                        s.lastActivityAt = now
                        s.disconnectedAt = null
                    }
                val idleFell = nextState.idle
                if (idleFell) nextState.idle = false
                nextState to (false to idleFell)
            }
        if (idleFell) broadcaster.broadcast(lobbyId, LobbyEvent.Idle(sessionId, idle = false))
    }

    /**
     * Records that [sessionId]'s WebSocket closed. Fires [LobbyEvent.ConnectionLost] immediately;
     * the session is held in the table for [disconnectGrace] before [tickOnce] forgets it.
     */
    suspend fun recordDisconnect(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ) {
        val now = clock.now()
        val emitted =
            transitionUnit(sessionId) { prior ->
                if (prior == null) return@transitionUnit null to false
                if (prior.disconnectedAt != null) return@transitionUnit prior to false
                prior.disconnectedAt = now
                prior to true
            }
        if (emitted) broadcaster.broadcast(lobbyId, LobbyEvent.ConnectionLost(sessionId))
    }

    /**
     * Records a reconnect of [sessionId] within the grace window. Clears `disconnectedAt` so
     * [tickOnce] will not forget the session; does not itself fire any edge event.
     */
    fun recordReconnect(sessionId: SessionId) {
        sessions.computeIfPresent(sessionId) { _, state ->
            state.disconnectedAt = null
            state
        }
    }

    /**
     * Records a server-authoritative cursor relocation triggered by an answer validation. Fires
     * [LobbyEvent.CursorBumped] regardless of the session's current presence state; callers
     * (e.g. an answer-validation use case) decide WHICH peer to bump.
     */
    suspend fun bumpCursor(
        lobbyId: LobbyId,
        sessionId: SessionId,
        position: Position,
        direction: GameClueDirection,
    ) {
        broadcaster.broadcast(
            lobbyId,
            LobbyEvent.CursorBumped(sessionId, position, direction),
        )
    }

    /**
     * Single-pass timer sweep. Walks every tracked session and fires whichever trailing edges
     * are due:
     *  - typing -> false when the last keystroke is older than [typingTrailingEdge],
     *  - idle -> true when the last activity is older than [idleThreshold] and the session is
     *    still connected,
     *  - forget the session entirely when its disconnect grace has elapsed.
     *
     * Test-friendly entrypoint (no infinite loop). Production wires this into [run].
     */
    suspend fun tickOnce() {
        val now = clock.now()
        for (sid in ArrayList(sessions.keys)) {
            var typingFell = false
            var idleRose = false
            var expired = false
            var lobbyId: LobbyId? = null
            sessions.computeIfPresent(sid) { _, state ->
                lobbyId = state.lobbyId
                typingFell =
                    state.typing &&
                    state.lastKeystrokeAt?.let { isOlderThan(it, now, typingTrailingEdge) } == true
                if (typingFell) state.typing = false
                idleRose =
                    !state.idle &&
                    state.disconnectedAt == null &&
                    isOlderThan(state.lastActivityAt, now, idleThreshold)
                if (idleRose) state.idle = true
                expired = state.disconnectedAt?.let { isOlderThan(it, now, disconnectGrace) } == true
                state
            }
            if (expired) sessions.remove(sid)
            lobbyId?.let { lid ->
                if (typingFell) broadcaster.broadcast(lid, LobbyEvent.Typing(sid, typing = false))
                if (idleRose) broadcaster.broadcast(lid, LobbyEvent.Idle(sid, idle = true))
            }
        }
    }

    /**
     * Launches the tick loop on [scope] and returns the controlling [Job]. Mirrors
     * [LobbyGarbageCollector.run]'s lifecycle: cancellation through [delay], log-and-continue on
     * non-cancellation failures.
     */
    fun run(
        scope: CoroutineScope,
        tickInterval: Duration = Duration.ofSeconds(1),
    ): Job =
        scope.launch(Dispatchers.Default) {
            log.info(
                "presence.aggregator.started typingTrailingEdgeMs={} idleThresholdMs={} disconnectGraceMs={}",
                typingTrailingEdge.toMillis(),
                idleThreshold.toMillis(),
                disconnectGrace.toMillis(),
            )
            while (isActive) {
                try {
                    tickOnce()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (cause: Throwable) {
                    log.warn("presence.aggregator.tick_failed cause={}", cause.message, cause)
                }
                delay(tickInterval.toMillis())
            }
        }

    /**
     * Apply [mutator] to the session row for [sessionId]. The mutator returns the new state and
     * a result; if the row was absent and the mutator returned null state, the map is left
     * untouched. Wraps [ConcurrentHashMap.compute] so concurrent record-style calls serialise on
     * the same key.
     */
    private inline fun <R> transition(
        sessionId: SessionId,
        crossinline mutator: (SessionState?) -> Pair<SessionState?, R>,
    ): R {
        var capturedResult: R? = null
        sessions.compute(sessionId) { _, prior ->
            val (next, result) = mutator(prior)
            capturedResult = result
            next
        }
        @Suppress("UNCHECKED_CAST")
        return capturedResult as R
    }

    /** Variant of [transition] for booleans; lets the unit tests see the row's state directly. */
    private inline fun transitionUnit(
        sessionId: SessionId,
        crossinline mutator: (SessionState?) -> Pair<SessionState?, Boolean>,
    ): Boolean = transition(sessionId, mutator)

    private fun blankState(
        lobbyId: LobbyId,
        now: Instant,
    ): SessionState =
        SessionState(
            lobbyId = lobbyId,
            lastKeystrokeAt = null,
            lastActivityAt = now,
            disconnectedAt = null,
            typing = false,
            idle = false,
        )

    private fun isOlderThan(
        instant: Instant,
        now: Instant,
        gap: Duration,
    ): Boolean = !now.isBefore(instant.plus(gap))
}
