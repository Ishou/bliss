package com.bliss.game.application.usecases

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.game.application.usecases.Samples.alice
import com.bliss.game.application.usecases.Samples.bob
import com.bliss.game.application.usecases.Samples.sessionA
import com.bliss.game.application.usecases.Samples.sessionB
import com.bliss.game.application.usecases.Samples.sessionC
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.LobbyTitle
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class ListLobbiesForSessionTest {
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val gridConfig = GridConfig(5, 5)

    // Defaults to IN_PROGRESS because, per the ADR-0039 amendment of
    // 2026-05-12, WAITING lobbies do not appear in the "Mes parties"
    // listing. Tests that explicitly cover lifecycle filtering pass the
    // state they need.
    private fun lobby(
        id: LobbyId,
        owner: SessionId,
        members: Map<SessionId, Pseudonym> = mapOf(owner to alice),
        state: LobbyLifecycleState = LobbyLifecycleState.IN_PROGRESS,
        lastActivityAt: Instant = baseInstant,
        title: LobbyTitle? = null,
        code: LobbyCode = LobbyCode.generate(),
    ): Lobby {
        val players = members.mapValues { (sid, name) -> Player(sid, name, baseInstant) }
        val game: GameSession? =
            when (state) {
                LobbyLifecycleState.WAITING -> null
                LobbyLifecycleState.IN_PROGRESS ->
                    GameSession(samplePuzzle(), emptyMap<Position, CellEntry>(), baseInstant, null)
                LobbyLifecycleState.COMPLETED ->
                    GameSession(
                        samplePuzzle(),
                        emptyMap<Position, CellEntry>(),
                        baseInstant,
                        baseInstant.plusSeconds(60),
                    )
            }
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = players,
            state = state,
            gridConfig = gridConfig,
            game = game,
            lastActivityAt = lastActivityAt,
            code = code,
            title = title,
        )
    }

    private fun samplePuzzle(): GamePuzzle = Samples.puzzle()

    @Test
    fun `returns empty list when the session is in no lobby`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val useCase = ListLobbiesForSession(repo)

            val out = useCase.invoke(sessionA)

            assertThat(out).isEmpty()
        }

    // ADR-0039 amendment 2026-05-12: WAITING lobbies are excluded from the
    // listing because they are "salons d'attente", not "parties". Only
    // IN_PROGRESS and COMPLETED lobbies are returned.
    @Test
    fun `returns IN_PROGRESS and COMPLETED lobbies and excludes WAITING`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val waiting =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.WAITING,
                    lastActivityAt = baseInstant.plusSeconds(10),
                )
            val inProgress =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.IN_PROGRESS,
                    lastActivityAt = baseInstant.plusSeconds(20),
                )
            val completed =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.COMPLETED,
                    lastActivityAt = baseInstant.plusSeconds(30),
                )
            repo.save(waiting)
            repo.save(inProgress)
            repo.save(completed)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out).hasSize(2)
            assertThat(out.map { it.state }).containsExactly(
                LobbyLifecycleState.COMPLETED,
                LobbyLifecycleState.IN_PROGRESS,
            )
        }

    // Explicit guard: a session with one WAITING and one IN_PROGRESS lobby
    // sees only the IN_PROGRESS one in "Mes parties".
    @Test
    fun `excludes WAITING when session has one WAITING and one IN_PROGRESS`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val waiting =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.WAITING,
                    lastActivityAt = baseInstant.plusSeconds(10),
                )
            val inProgress =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.IN_PROGRESS,
                    lastActivityAt = baseInstant.plusSeconds(20),
                )
            repo.save(waiting)
            repo.save(inProgress)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out).hasSize(1)
            assertThat(out[0].id).isEqualTo(inProgress.id)
            assertThat(out[0].state).isEqualTo(LobbyLifecycleState.IN_PROGRESS)
        }

    // COMPLETED lobbies remain in the listing so a user can review a
    // finished game from "Mes parties".
    @Test
    fun `returns COMPLETED lobbies`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val completed =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    state = LobbyLifecycleState.COMPLETED,
                    lastActivityAt = baseInstant.plusSeconds(30),
                )
            repo.save(completed)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out).hasSize(1)
            assertThat(out[0].id).isEqualTo(completed.id)
            assertThat(out[0].state).isEqualTo(LobbyLifecycleState.COMPLETED)
        }

    @Test
    fun `orders results by lastActivityAt descending`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val oldest =
                lobby(LobbyId.generate(), sessionA, lastActivityAt = baseInstant.plusSeconds(5))
            val middle =
                lobby(LobbyId.generate(), sessionA, lastActivityAt = baseInstant.plusSeconds(50))
            val newest =
                lobby(LobbyId.generate(), sessionA, lastActivityAt = baseInstant.plusSeconds(500))
            // Save in non-sorted order so the use case has to do the ordering.
            repo.save(middle)
            repo.save(oldest)
            repo.save(newest)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out.map { it.id }).containsExactly(newest.id, middle.id, oldest.id)
        }

    @Test
    fun `summary playerCount equals lobby players size`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val twoPlayer =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    members = mapOf(sessionA to alice, sessionB to bob),
                )
            repo.save(twoPlayer)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out).hasSize(1)
            assertThat(out[0].playerCount).isEqualTo(2)
        }

    @Test
    fun `summary title is null when unset and equals LobbyTitle when set`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val titled =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    lastActivityAt = baseInstant.plusSeconds(100),
                    title = LobbyTitle("Friday night puzzle"),
                )
            val untitled =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    lastActivityAt = baseInstant.plusSeconds(50),
                    title = null,
                )
            repo.save(titled)
            repo.save(untitled)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out).hasSize(2)
            assertThat(out[0].title).isEqualTo(LobbyTitle("Friday night puzzle"))
            assertThat(out[1].title).isNull()
        }

    @Test
    fun `returns only lobbies the queried session is a member of`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val aLobby = lobby(LobbyId.generate(), sessionA)
            val bLobby = lobby(LobbyId.generate(), sessionB)
            // Lobby with both members - sessionA should see it; querying for sessionC must not.
            val sharedLobby =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    members = mapOf(sessionA to alice, sessionB to bob),
                    lastActivityAt = baseInstant.plusSeconds(99),
                )
            repo.save(aLobby)
            repo.save(bLobby)
            repo.save(sharedLobby)

            val forA = ListLobbiesForSession(repo).invoke(sessionA)
            val forC = ListLobbiesForSession(repo).invoke(sessionC)

            assertThat(forA.map { it.id }.toSet()).isEqualTo(setOf(aLobby.id, sharedLobby.id))
            assertThat(forC).isEmpty()
        }

    @Test
    fun `returns lobbies the queried session owns even when not in the players map`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val ownedButNotJoined =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    members = mapOf(sessionB to bob),
                    lastActivityAt = baseInstant.plusSeconds(42),
                )
            repo.save(ownedButNotJoined)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out.map { it.id }).containsExactly(ownedButNotJoined.id)
        }

    @Test
    fun `does not duplicate when the session is both the owner and a player`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val ownedAndJoined =
                lobby(
                    LobbyId.generate(),
                    sessionA,
                    members = mapOf(sessionA to alice, sessionB to bob),
                )
            repo.save(ownedAndJoined)

            val out = ListLobbiesForSession(repo).invoke(sessionA)

            assertThat(out.map { it.id }).containsExactly(ownedAndJoined.id)
        }
}
