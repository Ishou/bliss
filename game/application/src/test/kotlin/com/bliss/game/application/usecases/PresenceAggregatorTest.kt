package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.PresenceBroadcaster
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Behavior pinned by these tests:
 *  - `recordKeystroke` fires the rising typing edge once per burst (not on every keystroke).
 *  - `recordKeystroke` clears a prior idle state and fires `idle=false` on the falling edge.
 *  - `recordFocus` is activity for the idle timer but NOT a typing event.
 *  - `tickOnce` fires the trailing typing edge after the configured idle gap.
 *  - `tickOnce` fires the rising idle edge after the configured threshold.
 *  - `tickOnce` forgets a session whose disconnect grace has elapsed.
 *  - `recordDisconnect` fires `connectionLost` and is idempotent.
 *  - `recordReconnect` cancels a pending disconnect grace without emitting any event.
 *  - `bumpCursor` always fires a `cursorBumped` event (no state guards).
 */
class PresenceAggregatorTest {
    private val lobbyId = LobbyId.generate()
    private val sessionA = Samples.sessionA
    private val sessionB = Samples.sessionB

    @Test
    fun `recordKeystroke fires the rising typing edge once per burst`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator = PresenceAggregator(clock, recorder)

            aggregator.recordKeystroke(lobbyId, sessionA)
            aggregator.recordKeystroke(lobbyId, sessionA)
            aggregator.recordKeystroke(lobbyId, sessionA)

            assertThat(recorder.eventsOfType<LobbyEvent.Typing>())
                .containsExactly(LobbyEvent.Typing(sessionA, typing = true))
        }

    @Test
    fun `tickOnce fires the trailing typing edge after the typing trailing-edge gap`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator =
                PresenceAggregator(
                    clock,
                    recorder,
                    typingTrailingEdge = Duration.ofMillis(1500),
                )

            aggregator.recordKeystroke(lobbyId, sessionA)
            recorder.clear()

            // Less than the trailing edge: no falling edge yet.
            clock.advance(Duration.ofMillis(1499))
            aggregator.tickOnce()
            assertThat(recorder.eventsOfType<LobbyEvent.Typing>()).isEmpty()

            // Past the trailing edge: typing=false fires once.
            clock.advance(Duration.ofMillis(2))
            aggregator.tickOnce()
            assertThat(recorder.eventsOfType<LobbyEvent.Typing>())
                .containsExactly(LobbyEvent.Typing(sessionA, typing = false))

            // A second tick at the same instant does NOT re-fire.
            recorder.clear()
            aggregator.tickOnce()
            assertThat(recorder.eventsOfType<LobbyEvent.Typing>()).isEmpty()
        }

    @Test
    fun `tickOnce fires the rising idle edge after the idle threshold and falls on next activity`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator =
                PresenceAggregator(
                    clock,
                    recorder,
                    idleThreshold = Duration.ofSeconds(30),
                )

            aggregator.recordFocus(lobbyId, sessionA)
            recorder.clear()

            // Just before threshold: no idle.
            clock.advance(Duration.ofSeconds(29))
            aggregator.tickOnce()
            assertThat(recorder.eventsOfType<LobbyEvent.Idle>()).isEmpty()

            // At the threshold: idle=true fires.
            clock.advance(Duration.ofSeconds(1))
            aggregator.tickOnce()
            assertThat(recorder.eventsOfType<LobbyEvent.Idle>())
                .containsExactly(LobbyEvent.Idle(sessionA, idle = true))

            // Activity returns: idle=false fires once.
            recorder.clear()
            aggregator.recordFocus(lobbyId, sessionA)
            assertThat(recorder.eventsOfType<LobbyEvent.Idle>())
                .containsExactly(LobbyEvent.Idle(sessionA, idle = false))
        }

    @Test
    fun `recordFocus is activity for idle but does not emit a typing event`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator = PresenceAggregator(clock, recorder)

            aggregator.recordFocus(lobbyId, sessionA)

            assertThat(recorder.eventsOfType<LobbyEvent.Typing>()).isEmpty()
        }

    @Test
    fun `recordDisconnect fires connectionLost once and is idempotent on a still-disconnected session`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator = PresenceAggregator(clock, recorder)

            aggregator.recordKeystroke(lobbyId, sessionA)
            recorder.clear()

            aggregator.recordDisconnect(lobbyId, sessionA)
            aggregator.recordDisconnect(lobbyId, sessionA)

            assertThat(recorder.eventsOfType<LobbyEvent.ConnectionLost>())
                .containsExactly(LobbyEvent.ConnectionLost(sessionA))
        }

    @Test
    fun `recordReconnect clears the disconnect grace and does not itself emit any event`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator =
                PresenceAggregator(
                    clock,
                    recorder,
                    disconnectGrace = Duration.ofSeconds(30),
                )

            aggregator.recordKeystroke(lobbyId, sessionA)
            aggregator.recordDisconnect(lobbyId, sessionA)
            recorder.clear()

            aggregator.recordReconnect(sessionA)

            // The reconnect call itself emits nothing (only future activity does).
            assertThat(recorder.events()).isEmpty()
            // And the session survives a tick that would otherwise have expired it.
            clock.advance(Duration.ofSeconds(60))
            recorder.clear() // ignore typing trailing-edge / idle rising-edge from the time gap
            aggregator.tickOnce()
            assertThat(aggregator.trackedSessionCount()).isEqualTo(1)
        }

    @Test
    fun `tickOnce forgets a session after the disconnect grace elapses`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator =
                PresenceAggregator(
                    clock,
                    recorder,
                    disconnectGrace = Duration.ofSeconds(30),
                )

            aggregator.recordKeystroke(lobbyId, sessionA)
            aggregator.recordDisconnect(lobbyId, sessionA)
            assertThat(aggregator.trackedSessionCount()).isEqualTo(1)

            clock.advance(Duration.ofSeconds(31))
            aggregator.tickOnce()

            assertThat(aggregator.trackedSessionCount()).isEqualTo(0)
        }

    @Test
    fun `bumpCursor always emits a cursorBumped event`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator = PresenceAggregator(clock, recorder)

            aggregator.bumpCursor(
                lobbyId,
                sessionB,
                Position(2, 0),
                GameClueDirection.DOWN,
            )

            assertThat(recorder.eventsOfType<LobbyEvent.CursorBumped>())
                .containsExactly(
                    LobbyEvent.CursorBumped(
                        sessionId = sessionB,
                        position = Position(2, 0),
                        direction = GameClueDirection.DOWN,
                    ),
                )
        }

    @Test
    fun `independent sessions track typing edges separately`() =
        runTest {
            val clock = FakeClock()
            val recorder = RecordingPresenceBroadcaster()
            val aggregator =
                PresenceAggregator(
                    clock,
                    recorder,
                    typingTrailingEdge = Duration.ofMillis(1500),
                )

            aggregator.recordKeystroke(lobbyId, sessionA)
            // sessionA's trailing edge will fire later; sessionB types in the meantime.
            clock.advance(Duration.ofMillis(500))
            aggregator.recordKeystroke(lobbyId, sessionB)
            clock.advance(Duration.ofMillis(1100))
            aggregator.tickOnce()

            // sessionA: 1600ms since last keystroke -> trailing edge fires.
            // sessionB: 1100ms since last keystroke -> trailing edge does not fire.
            val typingEvents = recorder.eventsOfType<LobbyEvent.Typing>()
            assertThat(typingEvents).contains(LobbyEvent.Typing(sessionA, typing = true))
            assertThat(typingEvents).contains(LobbyEvent.Typing(sessionB, typing = true))
            assertThat(typingEvents).contains(LobbyEvent.Typing(sessionA, typing = false))
            assertThat(typingEvents).doesNotContain(LobbyEvent.Typing(sessionB, typing = false))
        }
}

/**
 * In-memory [PresenceBroadcaster] for unit tests. Records every event in arrival order so the
 * test can assert on emission count, identity, and ordering. Thread-safe via the synchronized
 * underlying [ArrayList] copy on read.
 */
class RecordingPresenceBroadcaster : PresenceBroadcaster {
    private val recorded = mutableListOf<Pair<LobbyId, LobbyEvent>>()

    override suspend fun broadcast(
        lobbyId: LobbyId,
        event: LobbyEvent,
    ) {
        synchronized(recorded) { recorded.add(lobbyId to event) }
    }

    fun events(): List<LobbyEvent> = synchronized(recorded) { recorded.map { it.second }.toList() }

    inline fun <reified T : LobbyEvent> eventsOfType(): List<T> = events().filterIsInstance<T>()

    fun clear() {
        synchronized(recorded) { recorded.clear() }
    }
}
