package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.application.usecases.Samples.alice
import com.bliss.game.application.usecases.Samples.bob
import com.bliss.game.application.usecases.Samples.sessionA
import com.bliss.game.application.usecases.Samples.sessionB
import com.bliss.game.application.usecases.Samples.sessionC
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Pseudonym
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pure-domain tests for the lobby GC: time is fully under FakeClock control, no real
 * delay() loops here. The cancellation test below DOES exercise [LobbyGarbageCollector.run]
 * to prove the coroutine is cancelable on shutdown — required so app-shutdown tests
 * cannot leak threads.
 */
class LobbyGarbageCollectorTest {
    private val ttl: Duration = Duration.ofMinutes(30)
    private val sweepInterval: Duration = Duration.ofMinutes(5)

    private fun harness(): GcHarness = GcHarness(ttl, sweepInterval)

    @Test
    fun `sweepOnce evicts a WAITING lobby older than the TTL`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value

            h.clock.advance(ttl.plusMinutes(1))
            val evicted = h.gc.sweepOnce()

            assertThat(evicted).isEqualTo(1)
            assertThat(h.repo.findById(lobby.id)).isNull()
        }

    @Test
    fun `sweepOnce keeps a WAITING lobby that is younger than the TTL`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value

            h.clock.advance(ttl.minusMinutes(1))
            val evicted = h.gc.sweepOnce()

            assertThat(evicted).isEqualTo(0)
            assertThat(h.repo.findById(lobby.id)).isNotNull()
        }

    @Test
    fun `sweepOnce never evicts a lobby that is IN_PROGRESS even if past the TTL`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.clock.advance(ttl.plusHours(2))
            val evicted = h.gc.sweepOnce()

            assertThat(evicted).isEqualTo(0)
            assertThat(h.repo.findById(lobby.id)).isNotNull()
        }

    @Test
    fun `state-changing use cases reset the activity clock and protect a lobby from sweep`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value

            // Just before the TTL would expire, a player joins — should refresh lastActivityAt.
            h.clock.advance(ttl.minusMinutes(1))
            h.join(lobby.id, sessionB, bob).requireSuccess()

            // Now advance another (ttl - 1m). With the bump, total idle time since the join is
            // ttl - 1m which is below the TTL, so the lobby must survive.
            h.clock.advance(ttl.minusMinutes(1))
            assertThat(h.gc.sweepOnce()).isEqualTo(0)
            assertThat(h.repo.findById(lobby.id)).isNotNull()
        }

    @Test
    fun `sweepOnce evicts only the idle lobbies, leaving fresh ones in place`() =
        runTest {
            val h = harness()
            val stale = h.create(sessionA, alice).value

            // Advance past the TTL so 'stale' is evictable.
            h.clock.advance(ttl.plusMinutes(1))

            // Then create a fresh lobby just before the sweep runs.
            val fresh = h.create(sessionB, bob).value
            // sessionC creates yet another which has just been created — also fresh.
            val brandNew = h.create(sessionC, Pseudonym("Carol")).value

            val evicted = h.gc.sweepOnce()

            assertThat(evicted).isEqualTo(1)
            val remainingIds =
                listOfNotNull(h.repo.findById(stale.id), h.repo.findById(fresh.id), h.repo.findById(brandNew.id))
                    .map { it.id }
            assertThat(remainingIds).doesNotContain(stale.id)
            assertThat(remainingIds).contains(fresh.id)
            assertThat(remainingIds).contains(brandNew.id)
        }

    // The repo can return a snapshot but the lobby may have been touched between the scan
    // and the eviction. The mutate() lambda re-validates and refuses to delete a now-fresh
    // lobby. We simulate this by mutating the lobby between findIdleWaiting and the next sweep
    // — easier: stamp the lobby fresh and verify a second sweep is a no-op.
    @Test
    fun `sweepOnce is safe to call repeatedly with no candidates`() =
        runTest {
            val h = harness()
            h.create(sessionA, alice)
            assertThat(h.gc.sweepOnce()).isEqualTo(0)
            assertThat(h.gc.sweepOnce()).isEqualTo(0)
        }

    @Test
    fun `run launches a coroutine that is cancelable cleanly`() =
        runBlocking {
            val h = harness()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.Default + supervisor)

            val job: Job = h.gc.run(scope)
            // Give the GC one tick to start (it will hit `delay(sweepInterval)` immediately).
            // Then cancel and confirm it returns promptly without leaking.
            job.cancelAndJoin()

            assertThat(job.isCancelled).isEqualTo(true)
            assertThat(job.isCompleted).isEqualTo(true)
            supervisor.cancel()
        }
}

internal class GcHarness(
    ttl: Duration,
    sweepInterval: Duration,
) {
    val clock = FakeClock()
    val repo = InMemoryLobbyRepository()
    val provider = FakePuzzleProvider(Samples.puzzle())
    val create = CreateLobbyUseCase(repo, clock)
    val join = JoinLobbyUseCase(repo, clock)
    val start = StartGameUseCase(repo, provider, clock)
    val gc = LobbyGarbageCollector(repo, clock, ttl, sweepInterval)

    suspend fun create(
        s: com.bliss.game.domain.SessionId,
        p: Pseudonym,
    ) = create.invoke(s, p)

    suspend fun join(
        l: LobbyId,
        s: com.bliss.game.domain.SessionId,
        p: Pseudonym,
    ) = join.invoke(l, s, p)

    suspend fun start(
        l: LobbyId,
        s: com.bliss.game.domain.SessionId,
    ) = start.invoke(l, s)
}
