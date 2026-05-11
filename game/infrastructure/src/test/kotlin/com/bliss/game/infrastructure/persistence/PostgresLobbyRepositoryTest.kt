package com.bliss.game.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
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

    @Test
    fun `findBySessionId returns lobbies in every lifecycle state ordered by lastActivityAt desc`() =
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
                waitingLobby(id = LobbyId.generate(), owner = sessionB)
                    .copy(lastActivityAt = baseInstant.plusSeconds(120))
            repo.save(waiting)
            repo.save(inProgress)
            repo.save(completed)
            repo.save(otherSession)

            val result = repo.findBySessionId(sessionA)

            assertThat(result.map { it.id }).containsExactly(inProgress.id, completed.id, waiting.id)
        }

    @Test
    fun `findBySessionId returns empty when the session is not in any lobby`() =
        runTest {
            repo.save(waitingLobby(id = LobbyId.generate(), owner = sessionA))

            assertThat(repo.findBySessionId(sessionB)).isEmpty()
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
