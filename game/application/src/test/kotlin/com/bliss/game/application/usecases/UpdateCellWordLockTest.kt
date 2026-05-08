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

    // The v1 wire (grid/api/openapi.yaml LetterCell) deliberately strips
    // the canonical letter — solutions are server-private. So in
    // production HttpPuzzleProvider yields a GamePuzzle whose every
    // LetterCell.answer is null, even though the underlying grid does
    // know the answer. UpdateCellUseCase used to read `cell.answer`
    // directly; against this realistic puzzle it could never lock a
    // word, no matter what the player typed. That is the production
    // bug the user kept seeing in `make dev`: words filled, never
    // locked. Lock decisions must therefore be delegated to a
    // collaborator that asks grid (the only owner of the answers)
    // whether each candidate word is correct.
    @Test
    fun `wordLocked must still fire when the puzzle ships with no answers (v1 wire reality)`() =
        runTest {
            val puzzleWithoutAnswers =
                GamePuzzle(
                    id = UUID.fromString("0190e3c0-0000-7000-8000-000000000002"),
                    title = "Realistic v1 puzzle",
                    language = "fr",
                    width = 5,
                    height = 5,
                    cells =
                        listOf(
                            BlockCell(Position(0, 0)),
                            LetterCell(across01, answer = null),
                            LetterCell(across02, answer = null),
                            LetterCell(cross03, answer = null),
                            LetterCell(down13, answer = null),
                            LetterCell(down23, answer = null),
                        ),
                    clues =
                        listOf(
                            GameClue(acrossClueId, GameClueDirection.ACROSS, across01, 3, "PAS"),
                            GameClue(downClueId, GameClueDirection.DOWN, cross03, 3, "SEL"),
                        ),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            // Answers live with the grid (the only authority) — game-api
            // resolves them via the WordValidator port at runtime.
            val gridAnswers =
                mapOf(
                    across01 to Letter('P'),
                    across02 to Letter('A'),
                    cross03 to Letter('S'),
                    down13 to Letter('E'),
                    down23 to Letter('L'),
                )
            val h = Harness(puzzleWithoutAnswers, answers = gridAnswers)
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            val solved = h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()

            // Reproduces the user's "words never go valid" symptom: before the
            // WordValidator port the use case read `cell.answer`, found null
            // everywhere, and never locked anything. With the validator in
            // place, the grid-supplied answers drive the lock decision.
            val locked = solved.events.filterIsInstance<LobbyEvent.WordLocked>()
            assertThat(locked).hasSize(1)
            assertThat(locked[0].positions).containsExactlyInAnyOrder(across01, across02, cross03)
        }

    @Test
    fun `completing a word that crosses an already-locked word still locks the new word`() =
        runTest {
            // The fixture's two words share `cross03`. Lock the across word
            // first, then fill the rest of the down word. The shared cell
            // is part of a locked word but the down word as a whole is not
            // — `candidateWordsToCheck` was incorrectly skipping any word
            // that touched a locked cell, so subsequent perpendicular
            // words never validated. This is the user-reported "validation
            // does not happen when crossing an already validated word".
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()

            // Lock the across word first.
            h.write(lobby.id, sessionA, across01, Letter('P')).requireSuccess()
            h.write(lobby.id, sessionA, across02, Letter('A')).requireSuccess()
            h.write(lobby.id, sessionA, cross03, Letter('S')).requireSuccess()

            // Now type the rest of the down word (cross03 already correctly
            // filled and locked). Fill from the second cell to the last.
            h.write(lobby.id, sessionA, down13, Letter('E')).requireSuccess()
            val out = h.write(lobby.id, sessionA, down23, Letter('L')).requireSuccess()

            // The down word's cells (excluding the already-locked crossing)
            // must lock too.
            val locks = out.events.filterIsInstance<LobbyEvent.WordLocked>()
            assertThat(locks).hasSize(1)
            assertThat(locks[0].positions).containsExactlyInAnyOrder(down13, down23)
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
