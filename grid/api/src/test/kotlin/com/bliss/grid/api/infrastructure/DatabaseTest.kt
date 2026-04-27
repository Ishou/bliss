package com.bliss.grid.api.infrastructure

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * Integration tests for the Flyway-driven schema bootstrap (ADR-0013 §6).
 *
 * The Postgres-backed case skips automatically when no Docker daemon is
 * present; the unset-`DATABASE_URL` branch is always exercised.
 */
class DatabaseTest {
    @BeforeEach
    fun resetState() {
        System.clearProperty("DATABASE_URL")
        Database.stopForTesting()
    }

    @AfterEach
    fun teardown() {
        Database.stopForTesting()
        System.clearProperty("DATABASE_URL")
    }

    @Test
    fun `start is a no-op when DATABASE_URL is unset`() {
        // Path exercised by `./gradlew test` and any local boot without a DB:
        // we log a structured warning and return cleanly, no exception.
        Database.start()
        assertThat(Database.dataSource()).isNull()
    }

    @Test
    fun `toJdbcUrl converts CNPG-style postgres URI to JDBC form`() {
        val converted = Database.toJdbcUrl("postgres://app:secret@db.example:5432/wordsparrow?sslmode=require")
        assertThat(converted).isEqualTo("jdbc:postgresql://db.example:5432/wordsparrow?sslmode=require")
    }

    @Test
    fun `toJdbcUrl passes through an already-jdbc URL untouched`() {
        val jdbc = "jdbc:postgresql://localhost:5433/test"
        assertThat(Database.toJdbcUrl(jdbc)).isEqualTo(jdbc)
    }

    @Test
    fun `extractCredentials pulls user and password out of the URI`() {
        val (user, pw) = Database.extractCredentials("postgres://app:secret@db.example:5432/wordsparrow")
        assertThat(user).isEqualTo("app")
        assertThat(pw).isEqualTo("secret")
    }

    @Test
    fun `toJdbcUrl rejects unsupported scheme`() {
        assertFailure { Database.toJdbcUrl("mysql://host/db") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `toJdbcUrl wraps malformed URI as IllegalStateException`() {
        assertFailure { Database.toJdbcUrl("not a uri [with brackets]") }
            .isInstanceOf(IllegalStateException::class)
    }

    @Test
    fun `extractCredentials returns nulls for a jdbc URL`() {
        val (user, pw) = Database.extractCredentials("jdbc:postgresql://localhost/db")
        assertThat(user).isNull()
        assertThat(pw).isNull()
    }

    @Test
    fun `toJdbcUrl wraps IPv6 host in brackets`() {
        val converted = Database.toJdbcUrl("postgres://app:secret@[::1]:5432/wordsparrow")
        assertThat(converted).isEqualTo("jdbc:postgresql://[::1]:5432/wordsparrow")
    }

    @Test
    fun `toJdbcUrl always strips userinfo and prefixes jdbc for valid postgres URIs`() =
        runBlocking {
            checkAll(
                Arb.string(1..20, Codepoint.alphanumeric()),
                Arb.string(1..20, Codepoint.alphanumeric()),
                Arb.string(1..20, Codepoint.alphanumeric()),
                Arb.int(1024..65535),
            ) { user, pass, dbName, port ->
                val raw = "postgres://$user:$pass@db.example:$port/$dbName"
                val jdbc = Database.toJdbcUrl(raw)
                assertThat(jdbc.startsWith("jdbc:postgresql://")).isTrue()
                // userinfo must be stripped — check the literal "user:pass@" segment is gone
                assertThat(jdbc.contains("$user:$pass@")).isFalse()
            }
        }

    @Test
    fun `extractCredentials round-trips alphanumeric user and password`() =
        runBlocking {
            checkAll(
                Arb.string(1..20, Codepoint.alphanumeric()),
                Arb.string(1..20, Codepoint.alphanumeric()),
            ) { user, pass ->
                val raw = "postgres://$user:$pass@db.example:5432/db"
                val (extractedUser, extractedPass) = Database.extractCredentials(raw)
                assertThat(extractedUser).isEqualTo(user)
                assertThat(extractedPass).isEqualTo(pass)
            }
        }

    /**
     * Spins a real Postgres, points [Database.start] at it, then asserts:
     *  - the `words` table exists with the columns from ADR-0013 §3;
     *  - the `words_lang_len` index exists;
     *  - the `length` generated column is computed correctly on insert.
     *
     * Skips automatically on hosts without a Docker daemon.
     */
    @Test
    fun `start applies V1 migration against a real Postgres`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { pg ->
            pg.start()
            // Pass the JDBC URL via the same lookup path Database.start uses.
            // username/password come from the URL when present; we set them
            // here via system property, which Database.start reads instead of
            // env (env is process-global on the JVM).
            System.setProperty(
                "DATABASE_URL",
                "jdbc:postgresql://${pg.host}:${pg.firstMappedPort}/${pg.databaseName}?user=${pg.username}&password=${pg.password}",
            )

            Database.start()

            val ds = Database.dataSource()
            assertThat(ds).isNotNull()
            assertWordsTableMatchesAdr0013(ds!!)
        }
    }

    private fun assertWordsTableMatchesAdr0013(ds: DataSource) {
        ds.connection.use { conn ->
            val expectedColumns =
                arrayOf(
                    "id",
                    "word_id",
                    "word",
                    "language",
                    "length",
                    "difficulty",
                    "clue",
                    "source",
                    "source_license",
                    "frequency",
                    "created_at",
                )
            assertThat(queryStrings(conn, COLUMNS_SQL)).containsAtLeast(*expectedColumns)
            assertThat(queryStrings(conn, INDEXES_SQL)).contains("words_lang_len")

            // length is a generated column — insert without it and read back.
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                stmt.setString(1, "chat")
                stmt.executeUpdate()
            }
            val length =
                conn.prepareStatement("SELECT length FROM words WHERE word = ?").use { stmt ->
                    stmt.setString(1, "chat")
                    stmt.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        rs.getInt(1)
                    }
                }
            assertThat(length).isEqualTo(4)
        }
    }

    private fun queryStrings(
        conn: java.sql.Connection,
        sql: String,
    ): List<String> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    companion object {
        private val COLUMNS_SQL =
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'words'
            """.trimIndent()
        private val INDEXES_SQL =
            """
            SELECT indexname FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'words'
            """.trimIndent()
        private val INSERT_SQL =
            """
            INSERT INTO words (word, source, source_license)
            VALUES (?, 'hunspell-fr', 'MPL-2.0')
            """.trimIndent()
    }
}
