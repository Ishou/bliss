package com.bliss.game.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.isEqualTo
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Contract test for the V1 Flyway migration that creates the lobby tables.
 *
 * Mirrors the Testcontainers boot pattern from
 * grid/infrastructure/.../PostgresPuzzleRepositoryTest. The Flyway adapter
 * (PostgresLobbyRepository) lands in PR #5; this PR ships the SQL + this
 * test that proves the schema applies cleanly and enforces the documented
 * constraints (state CHECK + ON DELETE CASCADE).
 *
 * Wave B - PR #3, ADR-0039.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

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
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        if (!::dataSource.isInitialized) return
        dataSource.connection.use { conn ->
            // CASCADE removes lobby_players + lobby_cell_entries by FK on lobby_id.
            conn.createStatement().use { it.executeUpdate("TRUNCATE lobbies CASCADE") }
        }
    }

    @Test
    fun `migration creates the three lobby tables`() {
        val tables = mutableListOf<String>()
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name IN (?, ?, ?)
                     ORDER BY table_name
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, "lobbies")
                    ps.setString(2, "lobby_players")
                    ps.setString(3, "lobby_cell_entries")
                    ps.executeQuery().use { rs ->
                        while (rs.next()) tables += rs.getString("table_name")
                    }
                }
        }
        assertThat(tables).containsAtLeast("lobbies", "lobby_players", "lobby_cell_entries")
    }

    @Test
    fun `lobbies state CHECK rejects unknown values`() {
        val insertInvalid = {
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        INSERT INTO lobbies
                          (id, code, owner_session_id, state, grid_width, grid_height, last_activity_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, "lobbyBadX")
                        ps.setString(2, "BADCODE1")
                        ps.setObject(3, UUID.randomUUID())
                        ps.setString(4, "NOT_A_REAL_STATE")
                        ps.setInt(5, 10)
                        ps.setInt(6, 10)
                        ps.setTimestamp(7, Timestamp.from(Instant.parse("2026-05-11T10:00:00Z")))
                        ps.executeUpdate()
                    }
            }
        }
        assertThrows(SQLException::class.java) { insertInvalid() }
    }

    @Test
    fun `deleting a lobby cascades to players and cell entries`() {
        val lobbyId = "lobby01A"
        val ownerSession = UUID.randomUUID()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn
                .prepareStatement(
                    """
                    INSERT INTO lobbies
                      (id, code, owner_session_id, state, grid_width, grid_height, last_activity_at)
                    VALUES (?, ?, ?, 'WAITING', 12, 12, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, lobbyId)
                    ps.setString(2, "JOIN1234")
                    ps.setObject(3, ownerSession)
                    ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-05-11T10:00:00Z")))
                    ps.executeUpdate()
                }
            conn
                .prepareStatement(
                    """
                    INSERT INTO lobby_players (lobby_id, session_id, pseudonym, joined_at)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, lobbyId)
                    ps.setObject(2, ownerSession)
                    ps.setString(3, "Renard 1")
                    ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-05-11T10:00:00Z")))
                    ps.executeUpdate()
                }
            conn
                .prepareStatement(
                    """
                    INSERT INTO lobby_cell_entries
                      (lobby_id, row, col, letter, written_by_session_id, written_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, lobbyId)
                    ps.setInt(2, 0)
                    ps.setInt(3, 0)
                    ps.setString(4, "A")
                    ps.setObject(5, ownerSession)
                    ps.setTimestamp(6, Timestamp.from(Instant.parse("2026-05-11T10:00:05Z")))
                    ps.executeUpdate()
                }
            conn.commit()
        }

        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM lobbies WHERE id = ?").use { ps ->
                ps.setString(1, lobbyId)
                ps.executeUpdate()
            }
        }

        assertThat(countWhereLobby("lobby_players", lobbyId)).isEqualTo(0)
        assertThat(countWhereLobby("lobby_cell_entries", lobbyId)).isEqualTo(0)
    }

    @Test
    fun `lobby_cell_entries written_by_session_id accepts null for RGPD anonymisation`() {
        val lobbyId = "lobby01B"
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn
                .prepareStatement(
                    """
                    INSERT INTO lobbies
                      (id, code, owner_session_id, state, grid_width, grid_height, last_activity_at)
                    VALUES (?, ?, ?, 'IN_PROGRESS', 12, 12, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, lobbyId)
                    ps.setString(2, "JOIN5678")
                    ps.setObject(3, UUID.randomUUID())
                    ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-05-11T10:00:00Z")))
                    ps.executeUpdate()
                }
            conn
                .prepareStatement(
                    """
                    INSERT INTO lobby_cell_entries
                      (lobby_id, row, col, letter, written_by_session_id, written_at)
                    VALUES (?, ?, ?, ?, NULL, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, lobbyId)
                    ps.setInt(2, 1)
                    ps.setInt(3, 2)
                    ps.setString(4, "B")
                    ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-05-11T10:00:05Z")))
                    ps.executeUpdate()
                }
            conn.commit()
        }

        assertThat(countWhereLobby("lobby_cell_entries", lobbyId)).isEqualTo(1)
    }

    private fun countWhereLobby(
        table: String,
        lobbyId: String,
    ): Int {
        // table is a hardcoded test-only literal; not user input. Safe to interpolate.
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE lobby_id = ?").use { ps ->
                ps.setString(1, lobbyId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
