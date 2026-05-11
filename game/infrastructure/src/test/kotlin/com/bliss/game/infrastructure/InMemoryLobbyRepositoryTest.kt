package com.bliss.game.infrastructure

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Player
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryLobbyRepositoryTest {
    private val sessionA = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    private val alice = Pseudonym("Alice")
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val gridConfig = GridConfig(5, 5)

    private fun lobbyAt(
        id: LobbyId,
        joinedAt: Instant = baseInstant,
        ownerSessionId: SessionId = sessionA,
        state: LobbyLifecycleState = LobbyLifecycleState.WAITING,
        lastActivityAt: Instant = joinedAt,
        code: LobbyCode = LobbyCode.generate(),
    ): Lobby =
        Lobby(
            id = id,
            ownerSessionId = ownerSessionId,
            players = mapOf(ownerSessionId to Player(ownerSessionId, alice, joinedAt)),
            state = state,
            gridConfig = gridConfig,
            game = null,
            lastActivityAt = lastActivityAt,
            code = code,
        )

    @Test
    fun `save then findById returns the saved lobby`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val lobby = lobbyAt(LobbyId.generate())

            repo.save(lobby)

            assertThat(repo.findById(lobby.id)).isEqualTo(lobby)
        }

    @Test
    fun `findById returns null for unknown id`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            assertThat(repo.findById(LobbyId.generate())).isNull()
        }

    @Test
    fun `mutate applies the lambda and persists the new state`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val lobby = lobbyAt(LobbyId.generate())
            repo.save(lobby)

            val mutated = repo.mutate(lobby.id) { it.copy(state = LobbyLifecycleState.WAITING) }

            assertThat(mutated).isNotNull()
            assertThat(repo.findById(lobby.id)).isEqualTo(mutated)
        }

    @Test
    fun `mutate returns null when the lobby is absent`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val result = repo.mutate(LobbyId.generate()) { it }
            assertThat(result).isNull()
        }

    @Test
    fun `mutate returning null deletes the lobby atomically`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val lobby = lobbyAt(LobbyId.generate())
            repo.save(lobby)

            val out = repo.mutate(lobby.id) { null }

            assertThat(out).isNull()
            assertThat(repo.findById(lobby.id)).isNull()
        }

    @Test
    fun `delete removes the lobby`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val lobby = lobbyAt(LobbyId.generate())
            repo.save(lobby)

            repo.delete(lobby.id)

            assertThat(repo.findById(lobby.id)).isNull()
        }

    @Test
    fun `delete on absent lobby is a no-op`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            // Should not throw.
            repo.delete(LobbyId.generate())
        }

    // Verifies no lost writes under concurrent read-modify-write.
    @Test
    fun `concurrent mutate increments do not lose updates`() =
        runBlocking {
            val repo = InMemoryLobbyRepository()
            val id = LobbyId.generate()
            repo.save(lobbyAt(id))
            val iterations = 100

            coroutineScope {
                (1..iterations)
                    .map {
                        async(Dispatchers.Default) {
                            repo.mutate(id) { current ->
                                val owner = current.players.getValue(current.ownerSessionId)
                                val advanced = owner.joinedAt.plusSeconds(1)
                                current.copy(
                                    players =
                                        current.players +
                                            (current.ownerSessionId to owner.copy(joinedAt = advanced)),
                                )
                            }
                        }
                    }.awaitAll()
            }

            val finalJoinedAt =
                repo
                    .findById(id)!!
                    .players
                    .getValue(sessionA)
                    .joinedAt
            assertThat(finalJoinedAt).isEqualTo(baseInstant.plusSeconds(iterations.toLong()))
        }

    // Verifies no stranded state after mutate-vs-delete race, regardless of which wins the lock.
    @Test
    fun `mutate racing with delete leaves no stranded state`() =
        runBlocking {
            repeat(50) {
                val repo = InMemoryLobbyRepository()
                val id = LobbyId.generate()
                repo.save(lobbyAt(id))

                coroutineScope {
                    val mutator =
                        async(Dispatchers.Default) {
                            repo.mutate(id) { it.copy(state = LobbyLifecycleState.WAITING) }
                        }
                    val deleter =
                        async(Dispatchers.Default) {
                            repo.delete(id)
                        }
                    mutator.await()
                    deleter.await()
                }

                assertThat(repo.findById(id)).isNull()
                // A follow-up mutate must report absence, not blow up on a stranded lock.
                assertThat(repo.mutate(id) { it }).isNull()
            }
        }

    @Test
    fun `findWaitingByOwnerSession returns the WAITING lobby owned by the session`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val ownerLobby = lobbyAt(LobbyId.generate())
            repo.save(ownerLobby)

            val found = repo.findWaitingByOwnerSession(sessionA)

            assertThat(found).isNotNull()
            assertThat(found!!.id).isEqualTo(ownerLobby.id)
        }

    @Test
    fun `findWaitingByOwnerSession returns null when the owner has no WAITING lobby`() =
        runTest {
            val repo = InMemoryLobbyRepository()

            val found = repo.findWaitingByOwnerSession(sessionA)

            assertThat(found).isNull()
        }

    @Test
    fun `findWaitingByOwnerSession ignores lobbies owned by a different session`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val otherSession = SessionId("0190e3b2-1c45-7d2e-9a3f-c0d1e2f3a4b5")
            repo.save(lobbyAt(LobbyId.generate(), ownerSessionId = otherSession))

            val found = repo.findWaitingByOwnerSession(sessionA)

            assertThat(found).isNull()
        }

    @Test
    fun `findIdleWaiting returns lobbies whose lastActivityAt is at or before cutoff`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val stale = lobbyAt(LobbyId.generate(), lastActivityAt = baseInstant)
            val fresh = lobbyAt(LobbyId.generate(), lastActivityAt = baseInstant.plusSeconds(3600))
            repo.save(stale)
            repo.save(fresh)

            val cutoff = baseInstant.plusSeconds(60)
            val idle = repo.findIdleWaiting(cutoff)

            assertThat(idle).hasSize(1)
            assertThat(idle.map { it.id }).containsExactlyInAnyOrder(stale.id)
        }

    @Test
    fun `findIdleWaiting returns empty when no lobbies match the cutoff`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            repo.save(lobbyAt(LobbyId.generate(), lastActivityAt = baseInstant.plusSeconds(7200)))

            val idle = repo.findIdleWaiting(baseInstant)

            assertThat(idle).isEmpty()
        }

    @Test
    fun `findIdleCompleted returns completed lobbies at or before cutoff and excludes WAITING`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            val staleCompleted = completedLobbyAt(LobbyId.generate(), lastActivityAt = baseInstant)
            val freshCompleted =
                completedLobbyAt(LobbyId.generate(), lastActivityAt = baseInstant.plusSeconds(3600))
            val staleWaiting = lobbyAt(LobbyId.generate(), lastActivityAt = baseInstant)
            repo.save(staleCompleted)
            repo.save(freshCompleted)
            repo.save(staleWaiting)

            val idle = repo.findIdleCompleted(baseInstant.plusSeconds(60))

            assertThat(idle).hasSize(1)
            assertThat(idle.map { it.id }).containsExactlyInAnyOrder(staleCompleted.id)
        }

    @Test
    fun `findIdleCompleted returns empty when no completed lobbies are at or before cutoff`() =
        runTest {
            val repo = InMemoryLobbyRepository()
            repo.save(completedLobbyAt(LobbyId.generate(), lastActivityAt = baseInstant.plusSeconds(7200)))

            assertThat(repo.findIdleCompleted(baseInstant)).isEmpty()
        }

    private fun completedLobbyAt(
        id: LobbyId,
        ownerSessionId: SessionId = sessionA,
        lastActivityAt: Instant = baseInstant,
    ): Lobby {
        val puzzle =
            GamePuzzle(
                id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5b00"),
                title = "Sample",
                language = "fr",
                width = 5,
                height = 5,
                cells = emptyList(),
                clues = emptyList(),
                createdAt = baseInstant.minusSeconds(3600),
            )
        return Lobby(
            id = id,
            ownerSessionId = ownerSessionId,
            players = mapOf(ownerSessionId to Player(ownerSessionId, alice, baseInstant)),
            state = LobbyLifecycleState.COMPLETED,
            gridConfig = gridConfig,
            game =
                GameSession(
                    puzzle = puzzle,
                    entries = emptyMap(),
                    startedAt = baseInstant.minusSeconds(1800),
                    completedAt = baseInstant.minusSeconds(60),
                ),
            lastActivityAt = lastActivityAt,
            code = LobbyCode.generate(),
        )
    }
}
