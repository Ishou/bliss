package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Three-rule cascade (ADR-0039) verified end-to-end through the EraseSessionUseCase
 * against the test in-memory adapter. Idempotency is asserted on the no-data path.
 */
class EraseSessionUseCaseTest {
    private val baseInstant: Instant = Instant.parse("2026-05-11T10:00:00Z")
    private val target = Samples.sessionA
    private val other = Samples.sessionB
    private val third = Samples.sessionC

    @Test
    fun `rule 1 - owner is the sole player - lobby is deleted`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val id = LobbyId.generate()
            repo.save(waitingLobby(id, owner = target, others = emptyList()))
            val erase = EraseSessionUseCase(repo)

            val result = erase(target)

            assertThat(result.deletedLobbies).isEqualTo(1)
            assertThat(result.transferredLobbies).isEqualTo(0)
            assertThat(result.removedPlayerships).isEqualTo(0)
            assertThat(result.anonymisedEntries).isEqualTo(0)
            assertThat(repo.findById(id)).isNull()
        }

    @Test
    fun `rule 2 - owner with remaining players - ownership transfers to earliest joined remaining player`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val id = LobbyId.generate()
            // Owner (target) joined first; other joined +10s; third joined +20s.
            // Earliest joined among remaining is `other`.
            val lobby =
                inProgressLobby(
                    id = id,
                    owner = target,
                    extraPlayers =
                        listOf(
                            other to baseInstant.plusSeconds(10),
                            third to baseInstant.plusSeconds(20),
                        ),
                    entriesBy = listOf(target, target, other),
                )
            repo.save(lobby)
            val erase = EraseSessionUseCase(repo)

            val result = erase(target)

            assertThat(result.deletedLobbies).isEqualTo(0)
            assertThat(result.transferredLobbies).isEqualTo(1)
            assertThat(result.removedPlayerships).isEqualTo(1)
            assertThat(result.anonymisedEntries).isEqualTo(2)
            val after = repo.findById(id)
            assertThat(after).isNotNull()
            assertThat(after!!.ownerSessionId).isEqualTo(other)
            assertThat(after.players.keys).containsOnly(other, third)
            val sessionIds =
                after.game!!
                    .entries.values
                    .map { it.sessionId }
                    .toSet()
            // Two anon'd entries + one untouched entry by `other`.
            assertThat(sessionIds).containsOnly(SessionId.ANON, other)
        }

    @Test
    fun `rule 3 - non-owner is removed and entries anonymised`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val id = LobbyId.generate()
            val lobby =
                inProgressLobby(
                    id = id,
                    owner = other,
                    extraPlayers = listOf(target to baseInstant.plusSeconds(10)),
                    entriesBy = listOf(target),
                )
            repo.save(lobby)
            val erase = EraseSessionUseCase(repo)

            val result = erase(target)

            assertThat(result.deletedLobbies).isEqualTo(0)
            assertThat(result.transferredLobbies).isEqualTo(0)
            assertThat(result.removedPlayerships).isEqualTo(1)
            assertThat(result.anonymisedEntries).isEqualTo(1)
            val after = repo.findById(id)
            assertThat(after).isNotNull()
            assertThat(after!!.ownerSessionId).isEqualTo(other)
            assertThat(after.players.keys).containsOnly(other)
            assertThat(
                after.game!!
                    .entries.values
                    .first()
                    .sessionId,
            ).isEqualTo(SessionId.ANON)
        }

    @Test
    fun `idempotent - second call returns all zeros`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            repo.save(waitingLobby(LobbyId.generate(), owner = target))
            val erase = EraseSessionUseCase(repo)

            val first = erase(target)
            val second = erase(target)

            assertThat(first.deletedLobbies).isEqualTo(1)
            assertThat(second.deletedLobbies).isEqualTo(0)
            assertThat(second.transferredLobbies).isEqualTo(0)
            assertThat(second.removedPlayerships).isEqualTo(0)
            assertThat(second.anonymisedEntries).isEqualTo(0)
        }

    // ---- fixtures ------------------------------------------------------

    private fun waitingLobby(
        id: LobbyId,
        owner: SessionId,
        others: List<Pair<SessionId, Instant>> = emptyList(),
    ): Lobby {
        val players = LinkedHashMap<SessionId, Player>()
        players[owner] = Player(owner, Pseudonym("Owner"), baseInstant)
        for ((sid, joinedAt) in others) {
            players[sid] = Player(sid, Pseudonym("P-${sid.value.take(4)}"), joinedAt)
        }
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = players,
            state = LobbyLifecycleState.WAITING,
            gridConfig = GridConfig(5, 5),
            game = null,
            lastActivityAt = baseInstant,
            code = LobbyCode.generate(),
            title = null,
        )
    }

    private fun inProgressLobby(
        id: LobbyId,
        owner: SessionId,
        extraPlayers: List<Pair<SessionId, Instant>>,
        entriesBy: List<SessionId>,
    ): Lobby {
        val players = LinkedHashMap<SessionId, Player>()
        players[owner] = Player(owner, Pseudonym("Owner"), baseInstant)
        for ((sid, joinedAt) in extraPlayers) {
            players[sid] = Player(sid, Pseudonym("P-${sid.value.take(4)}"), joinedAt)
        }
        val puzzle = samplePuzzle()
        // One entry per author, placed at distinct positions.
        val entries =
            entriesBy.withIndex().associate { (idx, sid) ->
                Position(0, idx) to CellEntry(sid, Letter('A'), baseInstant.plusSeconds(30L + idx))
            }
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = players,
            state = LobbyLifecycleState.IN_PROGRESS,
            gridConfig = GridConfig(puzzle.width, puzzle.height),
            game =
                GameSession(
                    puzzle = puzzle,
                    entries = entries,
                    startedAt = baseInstant.plusSeconds(20),
                    completedAt = null,
                ),
            lastActivityAt = baseInstant.plusSeconds(60),
            code = LobbyCode.generate(),
            title = null,
        )
    }

    private fun samplePuzzle(): GamePuzzle =
        GamePuzzle(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5b00"),
            title = "Sample",
            language = "fr",
            width = 5,
            height = 5,
            cells =
                listOf(
                    BlockCell(Position(1, 0)),
                    LetterCell(Position(0, 0), Letter('A')),
                    LetterCell(Position(0, 1), Letter('B')),
                    LetterCell(Position(0, 2), Letter('C')),
                ),
            clues = emptyList(),
            createdAt = baseInstant.minusSeconds(120),
        )
}
