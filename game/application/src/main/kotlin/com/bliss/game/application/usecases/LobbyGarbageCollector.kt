package com.bliss.game.application.usecases

import com.bliss.game.application.ports.Clock
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.LobbyLifecycleState
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

/**
 * Sweeps lobbies whose `lastActivityAt` is older than the state-specific TTL
 * (ADR-0039 GC matrix). Companion to [CreateLobbyUseCase]'s per-session
 * idempotency: idempotency stops the user-visible "create-on-every-click"
 * path; this GC mops up lobbies that linger after a tab close, network drop,
 * crash, or a finished game whose retention window has elapsed.
 *
 * Decoupled from the WebSocket session manager: instead of polling
 * `SessionManager.connectedCount`, every state-changing use case stamps
 * `Lobby.lastActivityAt`. Consequence: a lobby with active sessions but zero
 * frames within the TTL is still evicted — that is intentional. The frontend's
 * heartbeat / reconnect ping will touch the lobby, and a truly silent client
 * is indistinguishable from an abandoned one.
 *
 * ADR-0039 GC matrix:
 *  - WAITING     → evict after [waitingTtl] (default 24h). Abandoned-lobby cleanup.
 *  - COMPLETED   → evict after [completedTtl] (default 7d). Finished-game retention.
 *  - IN_PROGRESS → NEVER evicted here. Neither query returns IN_PROGRESS rows;
 *                  in-progress lobbies are removed only when the last player leaves
 *                  (see `LeaveLobbyUseCase`).
 */
class LobbyGarbageCollector(
    private val repo: LobbyRepository,
    private val clock: Clock,
    val waitingTtl: Duration = Duration.ofHours(24),
    val completedTtl: Duration = Duration.ofDays(7),
    val sweepInterval: Duration = Duration.ofMinutes(5),
    private val log: Logger = LoggerFactory.getLogger(LobbyGarbageCollector::class.java),
) {
    /**
     * Single-pass sweep. Runs once and returns the total number of lobbies evicted
     * across both the WAITING and COMPLETED retention windows. Test-friendly
     * entrypoint: pure suspend, no infinite loop. Production wires this into [run]
     * to call repeatedly.
     */
    suspend fun sweepOnce(): Int {
        val now = clock.now()
        val waitingCutoff = now.minus(waitingTtl)
        val completedCutoff = now.minus(completedTtl)
        var evicted = 0
        evicted +=
            evictAll(
                candidates = repo.findIdleWaiting(waitingCutoff),
                cutoff = waitingCutoff,
                requiredState = LobbyLifecycleState.WAITING,
            )
        evicted +=
            evictAll(
                candidates = repo.findIdleCompleted(completedCutoff),
                cutoff = completedCutoff,
                requiredState = LobbyLifecycleState.COMPLETED,
            )
        if (evicted > 0) {
            log.info(
                "lobby.gc.evicted count={} waitingTtlHours={} completedTtlDays={}",
                evicted,
                waitingTtl.toHours(),
                completedTtl.toDays(),
            )
        }
        return evicted
    }

    private suspend fun evictAll(
        candidates: List<com.bliss.game.domain.Lobby>,
        cutoff: java.time.Instant,
        requiredState: LobbyLifecycleState,
    ): Int {
        var evicted = 0
        for (candidate in candidates) {
            // Re-validate inside mutate(): a player could have joined / written / re-opened
            // between the snapshot scan and the eviction. Only delete (return null) when the
            // lobby is still in [requiredState] and still beyond the cutoff.
            var deleted = false
            repo.mutate(candidate.id) { current ->
                if (current.state == requiredState && !current.lastActivityAt.isAfter(cutoff)) {
                    deleted = true
                    null // delete signal
                } else {
                    current
                }
            }
            if (deleted) evicted++
        }
        return evicted
    }

    /**
     * Launches the GC loop on [scope] and returns the controlling [Job]. The
     * coroutine runs `sweepOnce()` then suspends for [sweepInterval] until the
     * scope is cancelled. Cancellation propagates through `delay`, so calling
     * `job.cancel()` (or `scope.cancel()`) shuts the GC down cleanly without
     * a busy-wait or unkillable thread.
     */
    fun run(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Default) {
            log.info(
                "lobby.gc.started waitingTtlHours={} completedTtlDays={} sweepIntervalMinutes={}",
                waitingTtl.toHours(),
                completedTtl.toDays(),
                sweepInterval.toMinutes(),
            )
            while (isActive) {
                try {
                    sweepOnce()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (cause: Throwable) {
                    // Never let a sweep failure kill the loop — log and continue.
                    log.warn("lobby.gc.sweep_failed cause={}", cause.message, cause)
                }
                delay(sweepInterval.toMillis())
            }
        }
}
