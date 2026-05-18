package com.bliss.game.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.GameClue
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GameDefinitionClue
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.LobbyTitle
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

/**
 * Contract test for [PostgresLobbyRepository] against the real V1 schema
 * applied by Flyway inside a Testcontainers Postgres. Mirrors the boot
 * pattern in [com.bliss.grid.infrastructure.persistence.PostgresPuzzleRepositoryTest]
 * (grid context) and [MigrationTest] (this module).
 *
 * Covers every method on the LobbyRepository port plus the FOR-UPDATE
 * concurrency contract that the in-memory adapter promises via
 * ReentrantLock.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresLobbyRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PostgresLobbyRepository

    private val baseInstant: Instant = Instant.parse("2026-05-11T10:00:00Z")
    private val sessionA = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    private val sessionB = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c")
    private val sessionC = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d")

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        repo = PostgresLobbyRepository(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        if (!::repo.isInitialized) return
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("TRUNCATE lobbies CASCADE") }
        }
    }

    @Test
    fun `save then findById round-trips a WAITING lobby`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate())

            repo.save(lobby)
            val loaded = repo.findById(lobby.id)

            assertThat(loaded).isEqualTo(lobby)
        }

    @Test
    fun `save then findById round-trips an IN_PROGRESS lobby including game and entries`() =
        runTest {
            val lobby = inProgressLobby(id = LobbyId.generate())

            repo.save(lobby)
            val loaded = repo.findById(lobby.id)

            assertThat(loaded).isNotNull()
            assertThat(loaded!!).isEqualTo(lobby)
            val loadedGame = loaded.game
            val originalGame = lobby.game
            assertThat(loadedGame).isNotNull()
            assertThat(loadedGame!!.entries).isEqualTo(originalGame!!.entries)
            assertThat(loadedGame.lockedPositions).isEqualTo(originalGame.lockedPositions)
        }

    @Test
    fun `save is idempotent - saving the same lobby twice yields the same state`() =
        runTest {
            val lobby = inProgressLobby(id = LobbyId.generate())

            repo.save(lobby)
            repo.save(lobby)

            assertThat(repo.findById(lobby.id)).isEqualTo(lobby)
        }

    @Test
    fun `save replaces children on update - removed players and entries disappear`() =
        runTest {
            val original =
                inProgressLobby(id = LobbyId.generate()).let { l ->
                    val secondPlayer = Player(sessionB, Pseudonym("Bob"), baseInstant.plusSeconds(10))
                    l.copy(players = l.players + (sessionB to secondPlayer))
                }
            repo.save(original)

            // Remove Bob and one of the entries on update.
            val originalGame = original.game!!
            val firstEntry = originalGame.entries.entries.first()
            val trimmed =
                original.copy(
                    players = original.players - sessionB,
                    game = originalGame.copy(entries = mapOf(firstEntry.key to firstEntry.value)),
                )
            repo.save(trimmed)

            val loaded = repo.findById(original.id)
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.players).hasSize(1)
            assertThat(loaded.game!!.entries).hasSize(1)
        }

    @Test
    fun `findByCode returns null when code is unknown`() =
        runTest {
            assertThat(repo.findByCode(LobbyCode.generate())).isNull()
        }

    @Test
    fun `findByCode returns the saved lobby`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate())
            repo.save(lobby)

            val loaded = repo.findByCode(lobby.code)

            assertThat(loaded).isEqualTo(lobby)
        }

    @Test
    fun `findWaitingByOwnerSession returns null when no waiting lobby exists`() =
        runTest {
            assertThat(repo.findWaitingByOwnerSession(sessionA)).isNull()
        }

    @Test
    fun `findWaitingByOwnerSession returns the waiting lobby owned by the session`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            val loaded = repo.findWaitingByOwnerSession(sessionA)

            assertThat(loaded).isEqualTo(lobby)
        }

    @Test
    fun `findWaitingByOwnerSession does not return IN_PROGRESS lobbies for the same owner`() =
        runTest {
            val lobby = inProgressLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            assertThat(repo.findWaitingByOwnerSession(sessionA)).isNull()
        }

    @Test
    fun `findIdleWaiting returns waiting lobbies at or before the cutoff and excludes IN_PROGRESS`() =
        runTest {
            val idleWaiting =
                waitingLobby(
                    id = LobbyId.generate(),
                    lastActivityAt = baseInstant.minusSeconds(3600),
                )
            val freshWaiting =
                waitingLobby(
                    id = LobbyId.generate(),
                    owner = sessionB,
                    lastActivityAt = baseInstant.plusSeconds(3600),
                )
            val idleInProgress =
                inProgressLobby(
                    id = LobbyId.generate(),
                    owner = sessionC,
                ).let { it.copy(lastActivityAt = baseInstant.minusSeconds(3600)) }
            repo.save(idleWaiting)
            repo.save(freshWaiting)
            repo.save(idleInProgress)

            val result = repo.findIdleWaiting(baseInstant)

            assertThat(result.map { it.id }).containsExactly(idleWaiting.id)
        }

    @Test
    fun `findIdleCompleted returns completed lobbies at or before the cutoff and excludes WAITING and IN_PROGRESS`() =
        runTest {
            val idleCompleted =
                completedLobby(
                    id = LobbyId.generate(),
                    owner = sessionA,
                ).let { it.copy(lastActivityAt = baseInstant.minusSeconds(3600)) }
            val freshCompleted =
                completedLobby(
                    id = LobbyId.generate(),
                    owner = sessionB,
                ).let { it.copy(lastActivityAt = baseInstant.plusSeconds(3600)) }
            val idleWaiting =
                waitingLobby(
                    id = LobbyId.generate(),
                    owner = sessionC,
                    lastActivityAt = baseInstant.minusSeconds(3600),
                )
            repo.save(idleCompleted)
            repo.save(freshCompleted)
            repo.save(idleWaiting)

            val result = repo.findIdleCompleted(baseInstant)

            assertThat(result.map { it.id }).containsExactly(idleCompleted.id)
        }

    // ADR-0039 amendment 2026-05-12: WAITING lobbies are excluded from the
    // "Mes parties" listing; only IN_PROGRESS and COMPLETED are returned,
    // ordered by lastActivityAt descending.
    @Test
    fun `findBySessionId returns only IN_PROGRESS and COMPLETED ordered by lastActivityAt desc`() =
        runTest {
            val waiting =
                waitingLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant.minusSeconds(60))
            val inProgress =
                inProgressLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant.plusSeconds(60))
            val completed =
                completedLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant)
            val otherSession =
                inProgressLobby(id = LobbyId.generate(), owner = sessionB)
                    .copy(lastActivityAt = baseInstant.plusSeconds(120))
            repo.save(waiting)
            repo.save(inProgress)
            repo.save(completed)
            repo.save(otherSession)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(inProgress.id, completed.id)
        }

    @Test
    fun `findBySessionId excludes a WAITING lobby and returns the IN_PROGRESS one`() =
        runTest {
            val waiting =
                waitingLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant.plusSeconds(10))
            val inProgress =
                inProgressLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant.plusSeconds(20))
            repo.save(waiting)
            repo.save(inProgress)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(inProgress.id)
        }

    @Test
    fun `findBySessionId still returns COMPLETED lobbies`() =
        runTest {
            val completed =
                completedLobby(id = LobbyId.generate(), owner = sessionA)
                    .copy(lastActivityAt = baseInstant.plusSeconds(5))
            repo.save(completed)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(completed.id)
        }

    @Test
    fun `findBySessionId returns empty when the session is not in any lobby`() =
        runTest {
            repo.save(inProgressLobby(id = LobbyId.generate(), owner = sessionA))

            assertThat(repo.findBySessionId(sessionB)).isEmpty()
        }

    @Test
    fun `findBySessionId returns lobbies the session owns even after they left lobby_players`() =
        runTest {
            val ownedButLeft =
                Lobby(
                    id = LobbyId.generate(),
                    ownerSessionId = sessionA,
                    players = mapOf(sessionB to Player(sessionB, Pseudonym("Bob"), baseInstant)),
                    state = LobbyLifecycleState.IN_PROGRESS,
                    gridConfig = GridConfig(samplePuzzle().width, samplePuzzle().height),
                    game =
                        GameSession(
                            puzzle = samplePuzzle(),
                            entries = emptyMap(),
                            startedAt = baseInstant,
                            completedAt = null,
                            lockedPositions = emptySet(),
                        ),
                    lastActivityAt = baseInstant.plusSeconds(30),
                    code = LobbyCode.generate(),
                )
            repo.save(ownedButLeft)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(ownedButLeft.id)
            assertThat(result[0].ownerSessionId).isEqualTo(sessionA)
            assertThat(result[0].players.keys).containsOnly(sessionB)
        }

    @Test
    fun `findBySessionId does not duplicate when session is both owner and player`() =
        runTest {
            val ownedAndJoined = inProgressLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(ownedAndJoined)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(ownedAndJoined.id)
        }

    @Test
    fun `delete removes the lobby and cascades to players and entries`() =
        runTest {
            val lobby = inProgressLobby(id = LobbyId.generate())
            repo.save(lobby)

            repo.delete(lobby.id)

            assertThat(repo.findById(lobby.id)).isNull()
            assertThat(countChildRows("lobby_players", lobby.id)).isEqualTo(0)
            assertThat(countChildRows("lobby_cell_entries", lobby.id)).isEqualTo(0)
        }

    @Test
    fun `mutate read-modify-writes new state and returns it`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)
            val newcomer = Player(sessionB, Pseudonym("Bob"), baseInstant.plusSeconds(30))

            val mutated =
                repo.mutate(lobby.id) { current ->
                    current.copy(players = current.players + (sessionB to newcomer))
                }

            assertThat(mutated).isNotNull()
            assertThat(mutated!!.players).hasSize(2)
            assertThat(repo.findById(lobby.id)).isEqualTo(mutated)
        }

    @Test
    fun `mutate returning null atomically deletes the lobby`() =
        runTest {
            val lobby = inProgressLobby(id = LobbyId.generate())
            repo.save(lobby)

            val result = repo.mutate(lobby.id) { null }

            assertThat(result).isNull()
            assertThat(repo.findById(lobby.id)).isNull()
            assertThat(countChildRows("lobby_players", lobby.id)).isEqualTo(0)
            assertThat(countChildRows("lobby_cell_entries", lobby.id)).isEqualTo(0)
        }

    @Test
    fun `mutate throwing rolls back and leaves the lobby unchanged`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate())
            repo.save(lobby)

            val boom =
                runCatching {
                    repo.mutate(lobby.id) { error("boom") }
                }
            assertThat(boom.isFailure).isTrue()
            assertThat(repo.findById(lobby.id)).isEqualTo(lobby)
        }

    @Test
    fun `mutate returns null when the lobby does not exist`() =
        runTest {
            val result = repo.mutate(LobbyId.generate()) { error("mutator should not run") }
            assertThat(result).isNull()
        }

    @Test
    fun `eraseSession rule 1 - sole-owner lobby is deleted and children cascade`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            val result = repo.eraseSession(sessionA)

            assertThat(result.deletedLobbies).isEqualTo(1)
            assertThat(result.transferredLobbies).isEqualTo(0)
            assertThat(result.removedPlayerships).isEqualTo(0)
            assertThat(result.anonymisedEntries).isEqualTo(0)
            assertThat(repo.findById(lobby.id)).isNull()
            assertThat(countChildRows("lobby_players", lobby.id)).isEqualTo(0)
            assertThat(countChildRows("lobby_cell_entries", lobby.id)).isEqualTo(0)
        }

    @Test
    fun `eraseSession rule 2 - owner with remaining players - ownership transfers to earliest joined`() =
        runTest {
            val base = inProgressLobby(id = LobbyId.generate(), owner = sessionA)
            val withOthers =
                base.copy(
                    players =
                        base.players +
                            (sessionB to Player(sessionB, Pseudonym("Bob"), baseInstant.plusSeconds(10))) +
                            (sessionC to Player(sessionC, Pseudonym("Carol"), baseInstant.plusSeconds(20))),
                )
            repo.save(withOthers)

            val result = repo.eraseSession(sessionA)

            assertThat(result.deletedLobbies).isEqualTo(0)
            assertThat(result.transferredLobbies).isEqualTo(1)
            assertThat(result.removedPlayerships).isEqualTo(1)
            // Both seeded entries authored by sessionA (Alice) get anonymised.
            assertThat(result.anonymisedEntries).isEqualTo(2)
            val after = repo.findById(withOthers.id)
            assertThat(after).isNotNull()
            assertThat(after!!.ownerSessionId).isEqualTo(sessionB)
            assertThat(after.players.keys.toList()).containsOnly(sessionB, sessionC)
            // After anonymisation, every entry's sessionId is the ANON sentinel.
            after.game!!.entries.values.forEach {
                assertThat(it.sessionId).isEqualTo(SessionId.ANON)
            }
        }

    @Test
    fun `eraseSession rule 3 - non-owner is removed and entries anonymised`() =
        runTest {
            val base = inProgressLobby(id = LobbyId.generate(), owner = sessionA)
            // Add sessionB as a non-owner member; rewrite the seed entries
            // so one of them is authored by sessionB (so we can assert
            // anonymisation count of exactly 1).
            val withGuest =
                base.copy(
                    players =
                        base.players +
                            (sessionB to Player(sessionB, Pseudonym("Bob"), baseInstant.plusSeconds(10))),
                    game =
                        base.game!!.copy(
                            entries =
                                mapOf(
                                    Position(0, 0) to CellEntry(sessionA, Letter('B'), baseInstant.plusSeconds(5)),
                                    Position(0, 1) to CellEntry(sessionB, Letter('L'), baseInstant.plusSeconds(6)),
                                ),
                        ),
                )
            repo.save(withGuest)

            val result = repo.eraseSession(sessionB)

            assertThat(result.deletedLobbies).isEqualTo(0)
            assertThat(result.transferredLobbies).isEqualTo(0)
            assertThat(result.removedPlayerships).isEqualTo(1)
            assertThat(result.anonymisedEntries).isEqualTo(1)
            val after = repo.findById(withGuest.id)
            assertThat(after).isNotNull()
            assertThat(after!!.ownerSessionId).isEqualTo(sessionA) // unchanged
            assertThat(after.players.keys.toList()).containsOnly(sessionA)
            val anonEntry = after.game!!.entries[Position(0, 1)]
            assertThat(anonEntry).isNotNull()
            assertThat(anonEntry!!.sessionId).isEqualTo(SessionId.ANON)
            // sessionA's entry remains attributed.
            assertThat(after.game!!.entries[Position(0, 0)]!!.sessionId).isEqualTo(sessionA)
        }

    @Test
    fun `eraseSession is idempotent - second call returns all zeros`() =
        runTest {
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            val first = repo.eraseSession(sessionA)
            val second = repo.eraseSession(sessionA)

            assertThat(first.deletedLobbies).isEqualTo(1)
            assertThat(second.deletedLobbies).isEqualTo(0)
            assertThat(second.transferredLobbies).isEqualTo(0)
            assertThat(second.removedPlayerships).isEqualTo(0)
            assertThat(second.anonymisedEntries).isEqualTo(0)
        }

    @Test
    fun `eraseSession returns Empty when the session is in no lobby`() =
        runTest {
            assertThat(repo.eraseSession(sessionA).deletedLobbies).isEqualTo(0)
        }

    @Test
    fun `rebindAnonSeats updates only matching anon seats and returns touched lobby ids`() =
        runTest {
            val userId = UserId("11111111-1111-1111-1111-111111111111")
            // Lobby A: sessionA is anon (no userId). Should be rebound.
            val lobbyA = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            // Lobby B: sessionA is already authed (userId != null). Should NOT be touched.
            val lobbyBOwner = Player(sessionA, Pseudonym("Alice"), baseInstant, userId = userId)
            val lobbyB =
                waitingLobby(id = LobbyId.generate(), owner = sessionA).copy(
                    players = mapOf(sessionA to lobbyBOwner),
                )
            // Lobby C: a different anon session. Should NOT be touched.
            val lobbyC = waitingLobby(id = LobbyId.generate(), owner = sessionB)
            repo.save(lobbyA)
            repo.save(lobbyB)
            repo.save(lobbyC)

            val touched = repo.rebindAnonSeats(sessionA, userId, Pseudonym("Isho"))

            assertThat(touched).isEqualTo(setOf(lobbyA.id))
            val afterA = repo.findById(lobbyA.id)!!
            val seatA = afterA.players[sessionA]!!
            assertThat(seatA.userId).isEqualTo(userId)
            assertThat(seatA.pseudonym).isEqualTo(Pseudonym("Isho"))
            // Already-authed seat is untouched: pseudonym + userId unchanged.
            val afterB = repo.findById(lobbyB.id)!!
            assertThat(afterB.players[sessionA]!!.pseudonym).isEqualTo(Pseudonym("Alice"))
            assertThat(afterB.players[sessionA]!!.userId).isEqualTo(userId)
        }

    @Test
    fun `rebindAnonSeats is idempotent - second call returns empty set`() =
        runTest {
            val userId = UserId("11111111-1111-1111-1111-111111111111")
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            val first = repo.rebindAnonSeats(sessionA, userId, Pseudonym("Isho"))
            val second = repo.rebindAnonSeats(sessionA, userId, Pseudonym("Isho"))

            assertThat(first).isEqualTo(setOf(lobby.id))
            assertThat(second).isEmpty()
        }

    @Test
    fun `unbindUserSeats reverts authed seats back to anon and returns touched lobby ids`() =
        runTest {
            val userId = UserId("11111111-1111-1111-1111-111111111111")
            // Lobby A: seat carries userId. Should be unbound.
            val authedSeat = Player(sessionA, Pseudonym("Isho"), baseInstant, userId = userId)
            val lobbyA =
                waitingLobby(id = LobbyId.generate(), owner = sessionA).copy(
                    players = mapOf(sessionA to authedSeat),
                )
            // Lobby B: anon seat with different userId. Should NOT be touched.
            val otherUserId = UserId("22222222-2222-2222-2222-222222222222")
            val otherSeat = Player(sessionB, Pseudonym("Bob"), baseInstant, userId = otherUserId)
            val lobbyB =
                waitingLobby(id = LobbyId.generate(), owner = sessionB).copy(
                    players = mapOf(sessionB to otherSeat),
                )
            // Lobby C: pure-anon seat (null userId). Should NOT be touched.
            val lobbyC = waitingLobby(id = LobbyId.generate(), owner = sessionC)
            repo.save(lobbyA)
            repo.save(lobbyB)
            repo.save(lobbyC)

            val touched = repo.unbindUserSeats(userId, Pseudonym("Marmotte"))

            assertThat(touched).isEqualTo(setOf(lobbyA.id))
            val afterA = repo.findById(lobbyA.id)!!
            val seatA = afterA.players[sessionA]!!
            assertThat(seatA.userId).isEqualTo(null)
            assertThat(seatA.pseudonym).isEqualTo(Pseudonym("Marmotte"))
            // Other user's seat is untouched.
            val afterB = repo.findById(lobbyB.id)!!
            assertThat(afterB.players[sessionB]!!.userId).isEqualTo(otherUserId)
            assertThat(afterB.players[sessionB]!!.pseudonym).isEqualTo(Pseudonym("Bob"))
        }

    @Test
    fun `unbindUserSeats is idempotent - second call returns empty set`() =
        runTest {
            val userId = UserId("11111111-1111-1111-1111-111111111111")
            val authedSeat = Player(sessionA, Pseudonym("Isho"), baseInstant, userId = userId)
            val lobby =
                waitingLobby(id = LobbyId.generate(), owner = sessionA).copy(
                    players = mapOf(sessionA to authedSeat),
                )
            repo.save(lobby)

            val first = repo.unbindUserSeats(userId, Pseudonym("Marmotte"))
            val second = repo.unbindUserSeats(userId, Pseudonym("Marmotte"))

            assertThat(first).isEqualTo(setOf(lobby.id))
            assertThat(second).isEmpty()
        }

    @Test
    fun `save persists user_id and findById round-trips it`() =
        runTest {
            val userId = UserId("11111111-1111-1111-1111-111111111111")
            val authedSeat = Player(sessionA, Pseudonym("Isho"), baseInstant, userId = userId)
            val lobby =
                waitingLobby(id = LobbyId.generate(), owner = sessionA).copy(
                    players = mapOf(sessionA to authedSeat),
                )
            repo.save(lobby)

            val loaded = repo.findById(lobby.id)!!

            assertThat(loaded.players[sessionA]!!.userId).isEqualTo(userId)
        }

    @Test
    fun `concurrent mutate calls serialize via FOR UPDATE without lost updates`() {
        // runBlocking on the JVM dispatcher so the two `async` blocks land on
        // distinct OS threads — runTest's virtual scheduler would serialize them.
        runBlocking {
            val lobby = waitingLobby(id = LobbyId.generate(), owner = sessionA)
            repo.save(lobby)

            val bob = Player(sessionB, Pseudonym("Bob"), baseInstant.plusSeconds(10))
            val carol = Player(sessionC, Pseudonym("Carol"), baseInstant.plusSeconds(20))

            coroutineScope {
                val a =
                    async(Dispatchers.IO) {
                        repo.mutate(lobby.id) { current ->
                            // Tiny sleep widens the window in which a non-FOR-UPDATE
                            // implementation would lose an update.
                            Thread.sleep(50)
                            current.copy(players = current.players + (sessionB to bob))
                        }
                    }
                val b =
                    async(Dispatchers.IO) {
                        repo.mutate(lobby.id) { current ->
                            Thread.sleep(50)
                            current.copy(players = current.players + (sessionC to carol))
                        }
                    }
                awaitAll(a, b)
            }

            val loaded = repo.findById(lobby.id)
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.players.keys).containsAtLeast(sessionA, sessionB, sessionC)
            assertThat(loaded.players).hasSize(3)
        }
    }

    // ---- fixtures ------------------------------------------------------

    private fun waitingLobby(
        id: LobbyId,
        owner: SessionId = sessionA,
        lastActivityAt: Instant = baseInstant,
    ): Lobby {
        val ownerPlayer = Player(owner, Pseudonym("Alice"), baseInstant)
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = mapOf(owner to ownerPlayer),
            state = LobbyLifecycleState.WAITING,
            gridConfig = GridConfig(10, 10),
            game = null,
            lastActivityAt = lastActivityAt,
            code = LobbyCode.generate(),
            title = LobbyTitle("Partie test"),
        )
    }

    private fun inProgressLobby(
        id: LobbyId,
        owner: SessionId = sessionA,
    ): Lobby {
        val ownerPlayer = Player(owner, Pseudonym("Alice"), baseInstant)
        val puzzle = samplePuzzle()
        val entries =
            mapOf(
                Position(0, 0) to CellEntry(owner, Letter('B'), baseInstant.plusSeconds(5)),
                Position(0, 1) to CellEntry(owner, Letter('L'), baseInstant.plusSeconds(6)),
            )
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = mapOf(owner to ownerPlayer),
            state = LobbyLifecycleState.IN_PROGRESS,
            gridConfig = GridConfig(puzzle.width, puzzle.height),
            game =
                GameSession(
                    puzzle = puzzle,
                    entries = entries,
                    startedAt = baseInstant,
                    completedAt = null,
                    lockedPositions = setOf(Position(0, 0)),
                ),
            lastActivityAt = baseInstant.plusSeconds(60),
            code = LobbyCode.generate(),
            title = LobbyTitle("Vendredi 11 mai"),
        )
    }

    private fun completedLobby(
        id: LobbyId,
        owner: SessionId = sessionA,
    ): Lobby {
        val base = inProgressLobby(id, owner)
        val completedAt = baseInstant.plusSeconds(3600)
        return base.copy(
            state = LobbyLifecycleState.COMPLETED,
            game = base.game!!.copy(completedAt = completedAt),
        )
    }

    private fun samplePuzzle(): GamePuzzle {
        val clueId = UUID.fromString("00000000-0000-7000-8000-000000000001")
        return GamePuzzle(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5b00"),
            title = "Sample",
            language = "fr",
            width = 5,
            height = 5,
            cells =
                listOf(
                    LetterCell(Position(0, 0), Letter('B')),
                    LetterCell(Position(0, 1), Letter('L')),
                    BlockCell(Position(1, 0)),
                    DefinitionCell(
                        Position(2, 0),
                        clues =
                            listOf(
                                GameDefinitionClue(clueId, "Definition text", GameArrow.RIGHT),
                            ),
                    ),
                ),
            clues =
                listOf(
                    GameClue(
                        id = clueId,
                        direction = GameClueDirection.ACROSS,
                        start = Position(0, 0),
                        length = 2,
                        text = "Across clue",
                    ),
                ),
            createdAt = baseInstant.minusSeconds(120),
        )
    }

    private suspend fun countChildRows(
        table: String,
        id: LobbyId,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE lobby_id = ?").use { ps ->
                    ps.setString(1, id.value)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }
}
