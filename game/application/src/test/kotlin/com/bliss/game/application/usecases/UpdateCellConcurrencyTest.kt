package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.game.application.usecases.Samples.aPos
import com.bliss.game.application.usecases.Samples.alice
import com.bliss.game.application.usecases.Samples.bob
import com.bliss.game.application.usecases.Samples.pPos
import com.bliss.game.application.usecases.Samples.sessionA
import com.bliss.game.application.usecases.Samples.sessionB
import com.bliss.game.domain.Letter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * ADR-0018 §"Conflict policy" — last-write-wins by server timestamp. The repository's
 * per-lobby ReentrantLock serialises mutators, so the second mutator (whichever wins
 * the lock) observes the first's write and overwrites with its own [Instant].
 */
class UpdateCellConcurrencyTest {
    @Test
    fun `racing writes converge on the later timestamp`() =
        runBlocking {
            val h = Harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            h.start(lobby.id, sessionA).requireSuccess()

            // Sequential timestamps via FakeClock advancement; whichever runs second
            // sees the higher instant. We don't care which, only that the *final*
            // entry's timestamp matches the *current* clock value.
            h.clock.advance(Duration.ofMillis(100))
            val expectedFirst = h.clock.instant
            val firstWriter =
                async(Dispatchers.Default) {
                    h.write(lobby.id, sessionA, pPos, Letter('P')).requireSuccess()
                    expectedFirst
                }
            val firstStamp = firstWriter.await()
            h.clock.advance(Duration.ofMillis(50))
            val expectedSecond = h.clock.instant
            val secondWriter =
                async(Dispatchers.Default) {
                    h.write(lobby.id, sessionB, pPos, Letter('Q')).requireSuccess()
                    expectedSecond
                }
            secondWriter.await()

            val finalEntry =
                h.repo
                    .findById(lobby.id)
                    ?.game
                    ?.entries
                    ?.get(pPos)!!
            assertThat(finalEntry.letter).isEqualTo(Letter('Q'))
            assertThat(finalEntry.writtenAt).isEqualTo(expectedSecond)
            assertThat(finalEntry.writtenAt.isAfter(firstStamp)).isEqualTo(true)
        }

    @Test
    fun `parallel non-conflicting writes both succeed`() =
        runBlocking {
            val h = Harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            h.start(lobby.id, sessionA).requireSuccess()
            h.clock.instant = Instant.parse("2026-01-01T00:00:01Z")

            val writers =
                listOf(
                    async(Dispatchers.Default) { h.write(lobby.id, sessionA, pPos, Letter('P')).requireSuccess() },
                    async(Dispatchers.Default) { h.write(lobby.id, sessionB, aPos, Letter('A')).requireSuccess() },
                )
            writers.awaitAll()

            val state = h.repo.findById(lobby.id)!!
            assertThat(
                state.game
                    ?.entries
                    ?.get(pPos)
                    ?.letter,
            ).isEqualTo(Letter('P'))
            assertThat(
                state.game
                    ?.entries
                    ?.get(aPos)
                    ?.letter,
            ).isEqualTo(Letter('A'))
        }

    @Test
    fun `random fill order flips isSolved exactly on the final correct write`() =
        runTest {
            // Property: for any permutation of the two answer cells, isSolved is false
            // until the last write and true after. Two cells -> 2 perms; checkAll runs
            // many iterations of the same shuffle Arb to exercise both orderings.
            val positions = listOf(pPos to Letter('P'), aPos to Letter('A'))
            checkAll(50, Arb.shuffle(positions)) { order ->
                val h = Harness()
                val lobby = h.create(sessionA, alice).value
                h.start(lobby.id, sessionA).requireSuccess()
                order.forEachIndexed { i, (pos, letter) ->
                    h.clock.advance(Duration.ofSeconds(1))
                    val out = h.write(lobby.id, sessionA, pos, letter).requireSuccess()
                    val solved = out.value.game?.isSolved() == true
                    assertThat(solved).isEqualTo(i == order.lastIndex)
                }
            }
        }
}
