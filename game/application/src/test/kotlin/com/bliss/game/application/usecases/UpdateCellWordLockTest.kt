package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.usecases.Samples.alice
import com.bliss.game.application.usecases.Samples.sessionA
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.GameClue
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Position
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Word-level auto-validation behavior on top of [UpdateCellUseCase]. The
 * use case must lock words whose last letter is a correct fill, ignore
 * subsequent writes to locked cells, and emit a single [LobbyEvent.WordLocked]
 * carrying the union of newly-locked positions.
 */
class UpdateCellWordLockTest {
    // Across "PAS" at (0,1)-(0,3); down "SEL" at (0,3)-(2,3). Cells (0,3) is
    // the crossing — its letter 'S' belongs to both words.
    private val across01 = Position(0, 1)
    private val across02 = Position(0, 2)
    private val cross03 = Position(0, 3)
    private val down13 = Position(1, 3)
    private val down23 = Position(2, 3)

    private val acrossClueId = UUID.fromString("0190e3c0-0000-7000-8000-00000000a001")
    private val downClueId = UUID.fromString("0190e3c0-0000-7000-8000-00000000d001")

    private val puzzle =
        GamePuzzle(
            id = UUID.fromString("0190e3c0-0000-7000-8000-000000000001"),
            title = "Word-lock fixture",
            language = "fr",
            width = 5,
            height = 5,
            cells =
                listOf(
                    BlockCell(Position(0, 0)),
                    LetterCell(across01, Letter('P')),
                    LetterCell(across02, Letter('A')),
                    LetterCell(cross03, Letter('S')),
                    LetterCell(down13, Letter('E')),
                    LetterCell(down23, Letter('L')),
                ),
            clues =
                listOf(
                    GameClue(acrossClueId, GameClueDirection.ACROSS, across01, 3, "PAS"),
                    GameClue(downClueId, GameClueDirection.DOWN, cross03, 3, "SEL"),
                ),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun harness(): Harness = Harness(puzzle)

    @Test
    fun `filling the last correct letter of a word emits WordLocked with that word's positions`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            val solved = h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()

            // CellUpdated then WordLocked.
            assertThat(solved.events).hasSize(2)
            assertThat(solved.events[0]).isInstanceOf(LobbyEvent.CellUpdated::class)
            val locked = solved.events[1] as LobbyEvent.WordLocked
            assertThat(locked.positions).containsExactlyInAnyOrder(across01, across02, cross03)
            assertThat(solved.value.game?.lockedPositions).isEqualTo(locked.positions)
        }

    @Test
    fun `filling an incorrect last letter emits only CellUpdated`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            val wrong = h.write(lobby.id, sessionA, cross03, Letter('Z')).requireSuccess()

            assertThat(wrong.events).hasSize(1)
            assertThat(wrong.events[0]).isInstanceOf(LobbyEvent.CellUpdated::class)
            assertThat(wrong.value.game?.lockedPositions ?: emptySet()).isEmpty()
        }

    @Test
    fun `writes to a locked position are silent no-ops`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()
            val before = h.repo.findById(lobby.id)!!

            // Across word is now locked. Try to overwrite across01.
            val noop = h.write(lobby.id, sessionA, across01, Letter('Z')).requireSuccess()
            assertThat(noop.events).isEmpty()
            assertThat(
                noop.value.game
                    ?.entries
                    ?.get(across01)
                    ?.letter,
            ).isEqualTo(Letter('P'))
            assertThat(noop.value.lastActivityAt).isEqualTo(before.lastActivityAt)
        }

    @Test
    fun `crossing fill locks both intersecting words in a single event`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            // Pre-fill the non-crossing letters of both words.
            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            h.write(lobby.id, sessionA, down13, Letter('E')).requireSuccess()
            h.write(lobby.id, sessionA, down23, Letter('L')).requireSuccess()

            // Filling the crossing closes both words at once.
            val out = h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()

            // CellUpdated + a single WordLocked carrying the union.
            val lockedEvents = out.events.filterIsInstance<LobbyEvent.WordLocked>()
            assertThat(lockedEvents).hasSize(1)
            val locked = lockedEvents[0]
            assertThat(locked.positions).containsExactlyInAnyOrder(
                across01,
                across02,
                cross03,
                down13,
                down23,
            )
        }

    @Test
    fun `WordLocked is emitted alongside GameSolved on the final winning fill`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            h.write(lobby.id, sessionA, down13, Letter('E')).requireSuccess()
            h.write(lobby.id, sessionA, down23, Letter('L')).requireSuccess()

            val out = h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()

            // CellUpdated then WordLocked then GameSolved (order: writes, locks, solve).
            val types = out.events.map { it::class.simpleName }
            assertThat(types).isEqualTo(listOf("CellUpdated", "WordLocked", "GameSolved"))
        }
}
