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
 * Sweeps abandoned WAITING lobbies whose `lastActivityAt` is older than
 * [idleTtl]. Companion to [CreateLobbyUseCase]'s per-session idempotency:
 * idempotency stops the user-visible "create-on-every-click" path; this GC
 * mops up lobbies that linger after a tab close, network drop, or crash.
 *
 * Decoupled from the WebSocket session manager: instead of polling
 * `SessionManager.connectedCount`, every state-changing use case stamps
 * `Lobby.lastActivityAt`. Consequence: a lobby with active sessions but zero
 * frames in `idleTtl` is still evicted — that is intentional. The frontend's
 * heartbeat / reconnect ping will touch the lobby, and a truly silent client
 * is indistinguishable from an abandoned one.
 *
 * Only WAITING lobbies are evicted. IN_PROGRESS games stay alive until they
 * solve or the last player leaves (then `LeaveLobbyUseCase` deletes them).
 * COMPLETED lobbies are not GC'd here either — separate retention concern
 * handled by post-game UX in a follow-up.
 */
class LobbyGarbageCollector(
    private val repo: LobbyRepository,
    private val clock: Clock,
    val idleTtl: Duration,
    val sweepInterval: Duration,
    private val log: Logger = LoggerFactory.getLogger(LobbyGarbageCollector::class.java),
) {
    /**
     * Single-pass sweep. Runs once and returns the number of lobbies evicted.
     * Test-friendly entrypoint: pure suspend, no infinite loop. Production
     * wires this into [run] to call repeatedly.
     */
    suspend fun sweepOnce(): Int {
        val cutoff = clock.now().minus(idleTtl)
        val candidates = repo.findIdleWaiting(cutoff)
        var evicted = 0
        for (candidate in candidates) {
            // Re-validate inside mutate(): a player could have joined / written between the
            // snapshot scan and the eviction. Returning the same lobby is a no-op that does not
            // delete; only returning null deletes, and we do so only when the lobby is still
            // WAITING and still idle.
            var deleted = false
            repo.mutate(candidate.id) { current ->
                if (current.state == LobbyLifecycleState.WAITING && !current.lastActivityAt.isAfter(cutoff)) {
                    deleted = true
                    null // delete signal
                } else {
                    current
                }
            }
            if (deleted) evicted++
        }
        if (evicted > 0) {
            log.info("lobby.gc.evicted count={} idleTtlMinutes={}", evicted, idleTtl.toMinutes())
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
                "lobby.gc.started idleTtlMinutes={} sweepIntervalMinutes={}",
                idleTtl.toMinutes(),
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
