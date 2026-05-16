package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.util.PGobject
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

// Migrates only to V4, seeds NULL rows, then applies V5 to verify placement-geometry backfill.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V5BackfillTotalLetterCellsTest {
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
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `V5 backfills total_letter_cells from placement geometry across all directions`() {
        // 1. Apply V1..V4 only.
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("4")
            .load()
            .migrate()

        // Hand-computed distinct-cell counts, one fixture per direction and for disjoint/intersecting pairs.
        val fixtures =
            listOf(
                // a) Single RIGHT word "ABC" at clue (0,0), L=3 → (0,1)(0,2)(0,3) → 3.
                fixture(width = 5, height = 5, placements = listOf(placement("ABC", 0, 0, "RIGHT")), expected = 3),
                // b) Single DOWN word "ABCD" at clue (0,2), L=4 → (1..4,2) → 4.
                fixture(width = 5, height = 5, placements = listOf(placement("ABCD", 0, 2, "DOWN")), expected = 4),
                // c) Single DOWN_RIGHT word "AB" at clue (0,0), L=2 → (1,0)(1,1) → 2.
                fixture(width = 5, height = 5, placements = listOf(placement("AB", 0, 0, "DOWN_RIGHT")), expected = 2),
                // d) Single RIGHT_DOWN word "AB" at clue (0,0), L=2 → (0,1)(1,1) → 2.
                fixture(width = 5, height = 5, placements = listOf(placement("AB", 0, 0, "RIGHT_DOWN")), expected = 2),
                // e) Disjoint mix: RIGHT "ABC" at (0,0), DOWN "DEF" at (0,4) → 3 + 3 distinct → 6.
                fixture(
                    width = 5,
                    height = 5,
                    placements =
                        listOf(
                            placement("ABC", 0, 0, "RIGHT"),
                            placement("DEF", 0, 4, "DOWN"),
                        ),
                    expected = 6,
                ),
                // f) Real intersection: RIGHT "ABCD" at (1,0) and DOWN "EFGH" at (0,2).
                //    RIGHT: (1,1)(1,2)(1,3)(1,4); DOWN: (1,2)(2,2)(3,2)(4,2). Overlap (1,2) → 7 distinct.
                fixture(
                    width = 5,
                    height = 5,
                    placements =
                        listOf(
                            placement("ABCD", 1, 0, "RIGHT"),
                            placement("EFGH", 0, 2, "DOWN"),
                        ),
                    expected = 7,
                ),
                // g) Empty placements list → no rows in the LATERAL, no UPDATE.
                //    total_letter_cells stays NULL — V5 is conservative.
                fixture(width = 5, height = 5, placements = emptyList(), expected = null),
            )

        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO puzzles (puzzle_id, width, height, language, title, payload, hints_allowed) " +
                        "VALUES (?, ?, ?, 'fr', 't', ?, 3)",
                ).use { ps ->
                    for (f in fixtures) {
                        ps.setObject(1, f.id)
                        ps.setInt(2, f.width)
                        ps.setInt(3, f.height)
                        ps.setObject(
                            4,
                            PGobject().apply {
                                type = "jsonb"
                                value = f.payloadJson
                            },
                        )
                        ps.executeUpdate()
                    }
                }
        }

        val ids = fixtures.map { it.id }

        assertThat(countTotalLetterCellsNull(ids)).isEqualTo(ids.size)

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val actual = readTotalLetterCells(ids)
        val expected = fixtures.map { it.id to it.expected }
        assertThat(actual).containsExactlyInAnyOrder(*expected.toTypedArray())
    }

    private data class Fixture(
        val id: UUID,
        val payloadJson: String,
        val expected: Int?,
        val width: Int,
        val height: Int,
    )

    private fun fixture(
        width: Int,
        height: Int,
        placements: List<String>,
        expected: Int?,
    ): Fixture {
        val placementsJson = placements.joinToString(prefix = "[", postfix = "]", separator = ",")
        val payload = """{"width":$width,"height":$height,"placements":$placementsJson}"""
        return Fixture(UUID.randomUUID(), payload, expected, width, height)
    }

    private fun placement(
        wordText: String,
        clueRow: Int,
        clueCol: Int,
        direction: String,
    ): String =
        """{"wordText":"$wordText","wordLemma":"$wordText","clues":[{"text":"d","theme":null}],""" +
            """"chosenClueIndex":0,"cluePositionRow":$clueRow,"cluePositionColumn":$clueCol,""" +
            """"direction":"$direction"}"""

    private fun countTotalLetterCellsNull(ids: List<UUID>): Int =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "SELECT COUNT(*) FROM puzzles WHERE total_letter_cells IS NULL AND puzzle_id = ANY (?)",
                ).use { ps ->
                    ps.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun readTotalLetterCells(ids: List<UUID>): List<Pair<UUID, Int?>> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "SELECT puzzle_id, total_letter_cells FROM puzzles WHERE puzzle_id = ANY (?)",
                ).use { ps ->
                    ps.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<Pair<UUID, Int?>>()
                        while (rs.next()) {
                            val id = rs.getObject("puzzle_id") as UUID
                            val v = rs.getInt("total_letter_cells")
                            out += id to (if (rs.wasNull()) null else v)
                        }
                        out.toList()
                    }
                }
        }
}
