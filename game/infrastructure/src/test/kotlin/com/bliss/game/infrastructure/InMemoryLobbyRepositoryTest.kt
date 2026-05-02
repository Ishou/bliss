package com.bliss.game.infrastructure

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Lobby
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

class InMemoryLobbyRepositoryTest {
    private val sessionA = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    private val alice = Pseudonym("Alice")
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val gridConfig = GridConfig(5, 5)

    private fun lobbyAt(
        id: LobbyId,
        joinedAt: Instant = baseInstant,
    ): Lobby =
        Lobby(
            id = id,
            ownerSessionId = sessionA,
            players = mapOf(sessionA to Player(sessionA, alice, joinedAt)),
            state = LobbyLifecycleState.WAITING,
            gridConfig = gridConfig,
            game = null,
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

    /**
     * 100 coroutines each advance the owner's [Player.joinedAt] by one second
     * inside [InMemoryLobbyRepository.mutate]. Without a per-lobby lock, the
     * read-modify-write pattern would lose updates and the final joinedAt
     * would be earlier than baseInstant + 100s.
     */
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

    /**
     * Race [InMemoryLobbyRepository.mutate] against [InMemoryLobbyRepository.delete].
     * Whichever wins the lock, the post-condition is the same: store entry gone,
     * any subsequent mutate returns null. No stranded state.
     */
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
}
