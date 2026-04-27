package com.bliss.grid.api.infrastructure.words

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsNone
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * Real-Postgres integration test for [DatabaseWordRepository] (ADR-0013 §8).
 * Skips automatically on hosts without a Docker daemon.
 */
class DatabaseWordRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        pg.start()
        ds = newDataSource(pg)
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        seedFixture(ds)
    }

    @AfterEach
    fun tearDown() {
        if (this::ds.isInitialized) ds.close()
        if (this::pg.isInitialized) pg.stop()
    }

    @Test
    fun `findByLength returns folded fr words within 2-9 with non-null clues, excluding non-fr and null-clue rows`() {
        val repo = DatabaseWordRepository(ds)

        // chien, ecole (folded from école), arbre. lapin has null clue → excluded;
        // house is en → excluded; jardin (length 6) → excluded by length param.
        val len5 = repo.findByLength(5)
        assertThat(len5.map { it.text }).containsExactlyInAnyOrder("CHIEN", "ECOLE", "ARBRE")
        len5.forEach { word ->
            assertThat(word.text.length).isEqualTo(5)
            assertThat(word.text.all { it in 'A'..'Z' }).isTrue()
            assertThat(word.definition.isNotBlank()).isTrue()
        }
    }

    @Test
    fun `out-of-range lengths return empty (length 1 and 10 fixtures stay hidden)`() {
        val repo = DatabaseWordRepository(ds)
        assertThat(repo.findByLength(1)).isEmpty()
        assertThat(repo.findByLength(10)).isEmpty()
        assertThat(repo.findByLength(0)).isEmpty()
    }

    @Test
    fun `findByLengthAndPattern intersects with the diacritic-folded text`() {
        val repo = DatabaseWordRepository(ds)
        // ecole (folded from école) starts with 'E'. arbre starts with 'A'.
        val starting = repo.findByLengthAndPattern(5, mapOf(0 to 'E')).map { it.text }
        assertThat(starting).contains("ECOLE")
        assertThat(starting).containsNone("ARBRE", "CHIEN")
    }

    private fun seedFixture(ds: DataSource) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                // (word, language, difficulty, clue)
                listOf(
                    Fixture("a", "fr", "Lettre"), // length 1, excluded by window
                    Fixture("ai", "fr", "Conjugaison de avoir"), // length 2, included
                    Fixture("os", "fr", "Element du squelette"), // length 2, included
                    Fixture("chat", "fr", "Felin domestique"), // length 4, included
                    Fixture("chien", "fr", "Animal de compagnie"), // length 5, included
                    Fixture("école", "fr", "Lieu d'apprentissage"), // length 5 + diacritic, included → ECOLE
                    Fixture("arbre", "fr", "Vegetal a tronc"), // length 5, included
                    Fixture("lapin", "fr", clue = null), // length 5 null clue → excluded
                    Fixture("jardin", "fr", "Espace vert"), // length 6, included
                    Fixture("abracadabr", "fr", "Mot magique"), // length 10, excluded by window
                    Fixture("house", "en", "Habitation"), // wrong language → excluded
                ).forEach { f ->
                    stmt.setString(1, f.word)
                    stmt.setString(2, f.language)
                    if (f.clue == null) stmt.setNull(3, java.sql.Types.VARCHAR) else stmt.setString(3, f.clue)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    private data class Fixture(
        val word: String,
        val language: String,
        val clue: String?,
    )

    private companion object {
        // `length` is a generated column; we never set it directly.
        private val INSERT_SQL =
            """
            INSERT INTO words (word, language, clue, source, source_license)
            VALUES (?, ?, ?, 'hunspell-fr', 'MPL-2.0')
            """.trimIndent()

        private fun newDataSource(pg: PostgreSQLContainer<*>): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 2
                    poolName = "database-word-repository-test"
                },
            )
    }
}
