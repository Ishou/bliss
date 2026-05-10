package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.matches
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.usecases.Samples.aPos
import com.bliss.game.application.usecases.Samples.alice
import com.bliss.game.application.usecases.Samples.bob
import com.bliss.game.application.usecases.Samples.pPos
import com.bliss.game.application.usecases.Samples.sessionA
import com.bliss.game.application.usecases.Samples.sessionB
import com.bliss.game.application.usecases.Samples.sessionC
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration

class LobbyUseCasesTest {
    private fun harness(): Harness = Harness()

    @Test
    fun `CreateLobby places owner in WAITING and emits PlayerJoined`() =
        runTest {
            val h = harness()
            val result = h.create(sessionA, alice)

            assertThat(result.value.state).isEqualTo(LobbyLifecycleState.WAITING)
            assertThat(result.value.ownerSessionId).isEqualTo(sessionA)
            assertThat(result.value.players.keys).isEqualTo(setOf(sessionA))
            assertThat(result.events).hasSize(1)
            assertThat(result.events[0]).isInstanceOf(LobbyEvent.PlayerJoined::class)
        }

    @Test
    fun `CreateLobby starts the lobby at the 10x10 default grid size`() =
        runTest {
            val h = harness()
            val result = h.create(sessionA, alice)

            assertThat(result.value.gridConfig).isEqualTo(GridConfig(10, 10))
        }

    // Repro for the "infinite lobbies" DOS path: clicking "Create" repeatedly on the home
    // screen used to mint a fresh in-memory Lobby on every call. With idempotency the second
    // call returns the existing lobby and emits no event.
    @Test
    fun `CreateLobby is idempotent for an owner with an existing WAITING lobby`() =
        runTest {
            val h = harness()
            val first = h.create(sessionA, alice)
            h.clock.advance(Duration.ofSeconds(30))
            val second = h.create(sessionA, alice)

            assertThat(second.value.id).isEqualTo(first.value.id)
            assertThat(second.events).hasSize(0)
        }

    // Once the owner's lobby leaves WAITING (game starts) the next create call mints a fresh
    // lobby — owner is back in the matchmaking flow with their old game still IN_PROGRESS.
    @Test
    fun `CreateLobby mints a new lobby when the owner's previous lobby has left WAITING`() =
        runTest {
            val h = harness()
            val first = h.create(sessionA, alice).value
            h.start(first.id, sessionA).requireSuccess()
            val second = h.create(sessionA, alice)

            assertThat(second.value.id).isNotEqualTo(first.id)
            assertThat(second.events).hasSize(1)
        }

    // A lobby owned by a different session is NOT returned by the idempotency path.
    @Test
    fun `CreateLobby mints a new lobby for a different owner even if other lobbies exist`() =
        runTest {
            val h = harness()
            val a = h.create(sessionA, alice).value
            val b = h.create(sessionB, bob)

            assertThat(b.value.id).isNotEqualTo(a.id)
            assertThat(b.value.ownerSessionId).isEqualTo(sessionB)
        }

    @Test
    fun `JoinLobby adds a new player and emits PlayerJoined`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value

            val out = h.join(lobby.id, sessionB, bob).requireSuccess()

            assertThat(out.value.players.keys).isEqualTo(setOf(sessionA, sessionB))
            assertThat(out.events).hasSize(1)
        }

    @Test
    fun `JoinLobby is idempotent for the same sessionId reconnect`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val first = h.join(lobby.id, sessionB, bob).requireSuccess()
            h.clock.advance(Duration.ofSeconds(15))
            val second = h.join(lobby.id, sessionB, bob).requireSuccess()

            assertThat(second.value.players[sessionB]).isEqualTo(first.value.players[sessionB])
            assertThat(second.events).hasSize(0)
        }

    @Test
    fun `JoinLobby returns LobbyFull at capacity`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            // fill to 8
            repeat(7) { i ->
                h.join(lobby.id, validSession(i), Pseudonym("P$i")).requireSuccess()
            }
            val ninth = h.join(lobby.id, validSession(7), Pseudonym("Late"))
            assertThat((ninth as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.LobbyFull)
        }

    @Test
    fun `JoinLobby returns LobbyNotFound when missing`() =
        runTest {
            val h = harness()
            val ghost = LobbyId("zzzzzzzz")
            // The not-found branch fires before any code check, so an
            // arbitrary code is fine here.
            val out = h.joinWithCode(ghost, sessionA, alice, code = "A2B3C4")
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.LobbyNotFound)
        }

    // ADR-0027 — code-gate cases. New joiners must present the lobby's
    // `code`; reconnecting sessions (sessionId already a member) bypass the
    // check by construction so a refresh / tab-recovery flow never needs
    // the code.

    @Test
    fun `JoinLobby returns WrongCode when a new joiner presents a null code`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.joinWithCode(lobby.id, sessionB, bob, code = null)
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.WrongCode)
        }

    @Test
    fun `JoinLobby returns WrongCode when a new joiner presents a mismatched code`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            // Pattern-valid but not this lobby's code.
            val out = h.joinWithCode(lobby.id, sessionB, bob, code = "WRONG2")
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.WrongCode)
        }

    @Test
    fun `JoinLobby admits a new joiner when the code matches lobby code`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.joinWithCode(lobby.id, sessionB, bob, code = lobby.code.value).requireSuccess()
            assertThat(out.value.players.keys).isEqualTo(setOf(sessionA, sessionB))
        }

    // Reconnect bypass — the existing reconnect test (`JoinLobby is idempotent
    // for the same sessionId reconnect`) already exercises the happy path
    // because the convenience `join` resolves the lobby's code. The two
    // cases below pin the ADR-0027 invariant that reconnects do NOT consult
    // the code, regardless of what the client re-sends.

    @Test
    fun `JoinLobby reconnect succeeds even when code is null`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.joinWithCode(lobby.id, sessionB, bob, code = lobby.code.value).requireSuccess()
            h.clock.advance(Duration.ofSeconds(15))
            // Reconnect with null code — must still succeed (idempotent path).
            val second = h.joinWithCode(lobby.id, sessionB, bob, code = null).requireSuccess()
            assertThat(second.value.players[sessionB]).isNotNull()
            assertThat(second.events).hasSize(0)
        }

    @Test
    fun `JoinLobby reconnect succeeds even when code is wrong (regression guard)`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.joinWithCode(lobby.id, sessionB, bob, code = lobby.code.value).requireSuccess()
            // A client that re-sends a stale / mistyped code on reconnect must
            // not be locked out of their own lobby — guards against a future
            // refactor that mistakenly applies the code check before the
            // reconnect branch.
            val out = h.joinWithCode(lobby.id, sessionB, bob, code = "WRONG2").requireSuccess()
            assertThat(out.value.players[sessionB]).isNotNull()
            assertThat(out.events).hasSize(0)
        }

    // ADR-0029 — owner-only rotation. Tests verify the owner gate, the
    // in-place code update, and that the OLD code stops working.

    @Test
    fun `RotateLobbyCode mints a fresh code and emits CodeRotated`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val before = lobby.code.value
            val out = h.rotate(lobby.id, sessionA).requireSuccess()
            assertThat(out.value.code.value).isNotEqualTo(before)
            assertThat(out.value.code.value).matches(Regex("^[A-HJKM-NP-Z2-9]{6}$"))
            assertThat(out.events).containsExactly(LobbyEvent.CodeRotated(out.value.code))
        }

    @Test
    fun `RotateLobbyCode rejects non-owner with NotOwner and leaves code untouched`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            val before =
                h.repo
                    .findById(lobby.id)!!
                    .code.value
            val out = h.rotate(lobby.id, sessionB)
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.NotOwner)
            assertThat(
                h.repo
                    .findById(lobby.id)!!
                    .code.value,
            ).isEqualTo(before)
        }

    @Test
    fun `RotateLobbyCode returns LobbyNotFound when missing`() =
        runTest {
            val h = harness()
            val ghost = LobbyId("zzzzzzzz")
            val out = h.rotate(ghost, sessionA)
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.LobbyNotFound)
        }

    @Test
    fun `RotateLobbyCode keeps existing players (reconnect-bypass invariant unchanged)`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            h.rotate(lobby.id, sessionA).requireSuccess()
            val state = h.repo.findById(lobby.id)!!
            assertThat(state.players.keys).isEqualTo(setOf(sessionA, sessionB))
            // sessionB is already a member; reconnect bypasses the code check.
            val reconnect = h.joinWithCode(lobby.id, sessionB, bob, code = null).requireSuccess()
            assertThat(reconnect.value.players[sessionB]).isNotNull()
            assertThat(reconnect.events).hasSize(0)
        }

    @Test
    fun `JoinLobby with the old code fails after rotation`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val oldCode = lobby.code.value
            h.rotate(lobby.id, sessionA).requireSuccess()
            val rejected = h.joinWithCode(lobby.id, sessionB, bob, code = oldCode)
            assertThat((rejected as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.WrongCode)
            // The post-rotation code DOES admit a fresh joiner.
            val newCode =
                h.repo
                    .findById(lobby.id)!!
                    .code.value
            val admitted = h.joinWithCode(lobby.id, sessionB, bob, code = newCode).requireSuccess()
            assertThat(admitted.value.players.keys).isEqualTo(setOf(sessionA, sessionB))
        }

    @Test
    fun `RenameSelf updates pseudonym and emits PlayerRenamed`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.rename(lobby.id, sessionA, Pseudonym("Alicia")).requireSuccess()
            assertThat(out.value.players[sessionA]?.pseudonym).isEqualTo(Pseudonym("Alicia"))
            assertThat(out.events).containsExactly(LobbyEvent.PlayerRenamed(sessionA, Pseudonym("Alicia")))
        }

    @Test
    fun `RenameSelf rejects unknown sessionId with PlayerNotInLobby`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.rename(lobby.id, sessionB, Pseudonym("Mallory"))
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.PlayerNotInLobby)
        }

    @Test
    fun `SetGridConfig is owner-only`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()

            val notOwner = h.setConfig(lobby.id, sessionB, GridConfig(9, 9))
            assertThat((notOwner as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.NotOwner)

            val ok = h.setConfig(lobby.id, sessionA, GridConfig(9, 9)).requireSuccess()
            assertThat(ok.value.gridConfig).isEqualTo(GridConfig(9, 9))
            assertThat(ok.events).containsExactly(LobbyEvent.GridConfigChanged(GridConfig(9, 9)))
        }

    @Test
    fun `StartGame fetches puzzle, transitions to IN_PROGRESS, emits GameStarted`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val started = h.start(lobby.id, sessionA).requireSuccess()

            assertThat(started.value.state).isEqualTo(LobbyLifecycleState.IN_PROGRESS)
            assertThat(started.value.game).isNotNull()
            assertThat(started.events).hasSize(1)
            assertThat(started.events[0]).isInstanceOf(LobbyEvent.GameStarted::class)
        }

    @Test
    fun `StartGame rejects non-owner and refuses outside WAITING`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            val notOwner = h.start(lobby.id, sessionB)
            assertThat((notOwner as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.NotOwner)

            h.start(lobby.id, sessionA).requireSuccess()
            val twice = h.start(lobby.id, sessionA)
            assertThat((twice as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.InvalidState)
        }

    @Test
    fun `UpdateCell records entry and is rejected outside IN_PROGRESS`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            // outside IN_PROGRESS
            val rejected = h.write(lobby.id, sessionA, pPos, Letter('P'))
            assertThat((rejected as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.InvalidState)

            h.start(lobby.id, sessionA).requireSuccess()
            h.clock.advance(Duration.ofSeconds(3))
            val written = h.write(lobby.id, sessionA, pPos, Letter('P')).requireSuccess()
            assertThat(
                written.value.game
                    ?.entries
                    ?.get(pPos)
                    ?.letter,
            ).isEqualTo(Letter('P'))
            assertThat(written.events).hasSize(1)
            assertThat(written.events[0]).isInstanceOf(LobbyEvent.CellUpdated::class)
        }

    @Test
    fun `UpdateCell with null letter clears the entry`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()
            h.write(lobby.id, sessionA, pPos, Letter('Z')).requireSuccess()
            val cleared = h.write(lobby.id, sessionA, pPos, null).requireSuccess()
            assertThat(
                cleared.value.game
                    ?.entries
                    ?.get(pPos),
            ).isNull()
        }

    @Test
    fun `UpdateCell on the final correct letter emits CellUpdated then GameSolved and moves to COMPLETED`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()
            h.clock.advance(Duration.ofSeconds(2))
            h.write(lobby.id, sessionA, pPos, Letter('P')).requireSuccess()
            h.clock.advance(Duration.ofSeconds(3))
            val solved = h.write(lobby.id, sessionA, aPos, Letter('A')).requireSuccess()

            assertThat(solved.value.state).isEqualTo(LobbyLifecycleState.COMPLETED)
            assertThat(solved.value.game?.completedAt).isNotNull()
            assertThat(solved.events).hasSize(2)
            assertThat(solved.events[0]).isInstanceOf(LobbyEvent.CellUpdated::class)
            assertThat(solved.events[1]).isInstanceOf(LobbyEvent.GameSolved::class)
            val gs = solved.events[1] as LobbyEvent.GameSolved
            // started at t=0, P written at t=2s, A written at t=5s -> 5000 ms
            assertThat(gs.durationMs).isEqualTo(5000L)
        }

    @Test
    fun `LeaveLobby removes a non-owner and keeps owner stable`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.join(lobby.id, sessionB, bob).requireSuccess()
            val out = h.leave(lobby.id, sessionB).requireSuccess()
            val state = out.value ?: error("expected lobby to remain")
            assertThat(state.players.keys).isEqualTo(setOf(sessionA))
            assertThat(state.ownerSessionId).isEqualTo(sessionA)
            assertThat(out.events).containsExactly(LobbyEvent.PlayerLeft(sessionB))
        }

    @Test
    fun `LeaveLobby transfers ownership to the earliest joined when owner leaves`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.clock.advance(Duration.ofSeconds(1))
            h.join(lobby.id, sessionB, bob).requireSuccess()
            h.clock.advance(Duration.ofSeconds(1))
            h.join(lobby.id, sessionC, Pseudonym("Carol")).requireSuccess()

            val out = h.leave(lobby.id, sessionA).requireSuccess()
            val state = out.value ?: error("expected lobby to remain")
            assertThat(state.ownerSessionId).isEqualTo(sessionB)
        }

    @Test
    fun `LeaveLobby closes the lobby and emits LobbyClosed when last player leaves`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.leave(lobby.id, sessionA).requireSuccess()
            assertThat(out.value).isNull()
            assertThat(out.events).hasSize(2)
            assertThat(out.events[1]).isInstanceOf(LobbyEvent.LobbyClosed::class)
            assertThat(h.repo.findById(lobby.id)).isNull()
        }

    @Test
    fun `SetGridConfig returns InvalidState when lobby is IN_PROGRESS`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()
            val out = h.setConfig(lobby.id, sessionA, GridConfig(9, 9))
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.InvalidState)
        }

    @Test
    fun `LeaveLobby returns PlayerNotInLobby when player never joined`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            val out = h.leave(lobby.id, sessionB)
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.PlayerNotInLobby)
        }

    @Test
    fun `UpdateCell returns PlayerNotInLobby when caller is not in lobby`() =
        runTest {
            val h = harness()
            val lobby = h.create(sessionA, alice).value
            h.start(lobby.id, sessionA).requireSuccess()
            val out = h.write(lobby.id, sessionB, pPos, Letter('P'))
            assertThat((out as UseCaseOutcome.Failure).error).isEqualTo(UseCaseError.PlayerNotInLobby)
        }
}

/** Generates a UUIDv7 with deterministic-enough hex for max-capacity tests. */
private fun validSession(i: Int): SessionId {
    val low = "%04x".format(0xb000 + i)
    return SessionId("0190e3b2-1c45-7d2e-9a3f-c0d1e2f3$low")
}

internal class Harness(
    puzzle: com.bliss.game.domain.GamePuzzle = Samples.puzzle(),
    answers: Map<com.bliss.game.domain.Position, com.bliss.game.domain.Letter> =
        run {
            // Default answers: extract from the puzzle's LetterCells. Existing
            // tests that hand-construct puzzles with answers on letter cells
            // (the pre-validator era of game/domain) keep working without
            // every test having to plumb a separate answer table.
            val map = mutableMapOf<com.bliss.game.domain.Position, com.bliss.game.domain.Letter>()
            for (cell in puzzle.cells) {
                if (cell is com.bliss.game.domain.LetterCell) cell.answer?.let { map[cell.position] = it }
            }
            map
        },
) {
    val clock = FakeClock()
    val repo = InMemoryLobbyRepository()
    val provider = FakePuzzleProvider(puzzle)
    val wordValidator = FakeWordValidator(answers)
    val create = CreateLobbyUseCase(repo, clock)
    val join = JoinLobbyUseCase(repo, clock)
    val rename = RenameSelfUseCase(repo, clock)
    val setConfig = SetGridConfigUseCase(repo, clock)
    val start = StartGameUseCase(repo, provider, clock)
    val update = UpdateCellUseCase(repo, clock, wordValidator)
    val leave = LeaveLobbyUseCase(repo, clock)
    val rotateCode = RotateLobbyCodeUseCase(repo, clock)

    suspend fun create(
        s: SessionId,
        p: Pseudonym,
    ) = create.invoke(s, p)

    /**
     * Convenience join — resolves the lobby's canonical code so the test
     * site exercises the happy-path. Tests that need to probe the
     * ADR-0027 code-gate deliberately use [joinWithCode] and pass a
     * wrong/null value explicitly. (A `code: String? = repo.findById(l)?.code?.value`
     * default would compile-fail: Kotlin disallows suspend calls in default
     * parameter values.)
     */
    suspend fun join(
        l: LobbyId,
        s: SessionId,
        p: Pseudonym,
    ): UseCaseOutcome<Lobby> {
        val code = repo.findById(l)?.code?.value
        return join.invoke(l, s, p, code)
    }

    suspend fun joinWithCode(
        l: LobbyId,
        s: SessionId,
        p: Pseudonym,
        code: String?,
    ) = join.invoke(l, s, p, code)

    suspend fun rename(
        l: LobbyId,
        s: SessionId,
        p: Pseudonym,
    ) = rename.invoke(l, s, p)

    suspend fun setConfig(
        l: LobbyId,
        s: SessionId,
        g: GridConfig,
    ) = setConfig.invoke(l, s, g)

    suspend fun start(
        l: LobbyId,
        s: SessionId,
    ) = start.invoke(l, s)

    suspend fun write(
        l: LobbyId,
        s: SessionId,
        p: Position,
        c: Letter?,
    ) = update.invoke(l, s, p, c)

    suspend fun leave(
        l: LobbyId,
        s: SessionId,
    ) = leave.invoke(l, s)

    suspend fun rotate(
        l: LobbyId,
        s: SessionId,
    ) = rotateCode.invoke(l, s)
}

internal fun <T> UseCaseOutcome<T>.requireSuccess(): UseCaseResult<T> =
    when (this) {
        is UseCaseOutcome.Success -> result
        is UseCaseOutcome.Failure -> error("expected success, got $error")
    }
