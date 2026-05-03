package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.lexicon.DbnarySense
import com.bliss.grid.domain.lexicon.DbnaryWord
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDbnaryRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: JdbcDbnaryRepository

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
        val migrations =
            requireNotNull(System.getProperty("flyway.test.migrations")) {
                "flyway.test.migrations system property must be set by build.gradle.kts"
            }
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        repo = JdbcDbnaryRepository(dataSource)
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
            conn.createStatement().use { it.executeUpdate("TRUNCATE dbnary_words CASCADE") }
        }
    }

    @Test
    fun `upsertAll inserts a fresh entry with senses and synonyms`() {
        val word =
            DbnaryWord(
                lemma = "voiture",
                pos = "noun",
                senses =
                    listOf(
                        DbnarySense(0, "Vehicule a roues."),
                        DbnarySense(1, "Wagon de train.", register = "Chemin de fer"),
                    ),
                synonyms = listOf("bagnole", "automobile"),
            )

        val report = repo.upsertAll(sequenceOf(word))

        assertThat(report.wordsInserted).isEqualTo(1)
        assertThat(report.wordsUpdated).isEqualTo(0)
        assertThat(report.sensesWritten).isEqualTo(2)
        assertThat(report.synonymsWritten).isEqualTo(2)
        assertThat(repo.countByLanguage("fr")).isEqualTo(1L)

        val readBack = repo.findOne("fr", "voiture", "noun")
        assertThat(readBack).isNotNull()
        assertThat(readBack!!.senses.size).isEqualTo(2)
        assertThat(readBack.senses[0]).isEqualTo(DbnarySense(0, "Vehicule a roues."))
        assertThat(readBack.senses[1]).isEqualTo(DbnarySense(1, "Wagon de train.", "Chemin de fer"))
        assertThat(readBack.synonyms).containsExactlyInAnyOrder("bagnole", "automobile")
    }

    @Test
    fun `upsertAll on an existing key replaces senses and synonyms atomically`() {
        val v1 =
            DbnaryWord(
                lemma = "pull",
                pos = "noun",
                senses = listOf(DbnarySense(0, "Sens un.")),
                synonyms = listOf("chandail", "tricot"),
            )
        repo.upsertAll(sequenceOf(v1))

        val v2 =
            DbnaryWord(
                lemma = "pull",
                pos = "noun",
                senses =
                    listOf(
                        DbnarySense(0, "Sens un revise."),
                        DbnarySense(1, "Sens deux nouveau."),
                    ),
                synonyms = listOf("chandail"),
            )
        val report = repo.upsertAll(sequenceOf(v2))

        assertThat(report.wordsInserted).isEqualTo(0)
        assertThat(report.wordsUpdated).isEqualTo(1)
        assertThat(report.sensesWritten).isEqualTo(2)
        assertThat(report.synonymsWritten).isEqualTo(1)

        val readBack = repo.findOne("fr", "pull", "noun")!!
        assertThat(readBack.senses.map { it.definitionText })
            .containsExactly("Sens un revise.", "Sens deux nouveau.")
        assertThat(readBack.synonyms).containsExactly("chandail")
    }

    @Test
    fun `noun and verb of the same lemma are distinct rows`() {
        repo.upsertAll(
            sequenceOf(
                DbnaryWord(lemma = "pull", pos = "noun", senses = listOf(DbnarySense(0, "Le tricot."))),
                DbnaryWord(lemma = "pull", pos = "verb", senses = listOf(DbnarySense(0, "Tirer."))),
            ),
        )
        assertThat(repo.countByLanguage("fr")).isEqualTo(2L)
        assertThat(repo.findOne("fr", "pull", "noun")!!.senses[0].definitionText).isEqualTo("Le tricot.")
        assertThat(repo.findOne("fr", "pull", "verb")!!.senses[0].definitionText).isEqualTo("Tirer.")
    }

    @Test
    fun `findOne returns null when the entry does not exist`() {
        assertThat(repo.findOne("fr", "absent", "noun")).isNull()
    }

    @Test
    fun `deleteByLanguage cascades to senses and synonyms`() {
        repo.upsertAll(
            sequenceOf(
                DbnaryWord(
                    lemma = "voiture",
                    pos = "noun",
                    senses = listOf(DbnarySense(0, "Vehicule.")),
                    synonyms = listOf("bagnole"),
                ),
                DbnaryWord(
                    lemma = "house",
                    pos = "noun",
                    language = "en",
                    senses = listOf(DbnarySense(0, "A dwelling.")),
                ),
            ),
        )

        val deleted = repo.deleteByLanguage("fr")

        assertThat(deleted).isEqualTo(1)
        assertThat(repo.countByLanguage("fr")).isEqualTo(0L)
        assertThat(repo.countByLanguage("en")).isEqualTo(1L)

        // Cascade: senses + synonyms for the French entry must be gone too.
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM dbnary_senses").use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isEqualTo(1L) // only the English one
                }
                stmt.executeQuery("SELECT COUNT(*) FROM dbnary_synonyms").use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isEqualTo(0L)
                }
            }
        }
    }

    @Test
    fun `findOne preserves senses ordered by senseIndex`() {
        repo.upsertAll(
            sequenceOf(
                DbnaryWord(
                    lemma = "manger",
                    pos = "verb",
                    senses =
                        listOf(
                            DbnarySense(2, "Sens trois."),
                            DbnarySense(0, "Sens un."),
                            DbnarySense(1, "Sens deux."),
                        ),
                ),
            ),
        )

        val readBack = repo.findOne("fr", "manger", "verb")!!
        assertThat(readBack.senses.map { it.senseIndex }).containsExactly(0, 1, 2)
        assertThat(readBack.senses.map { it.definitionText })
            .containsExactly("Sens un.", "Sens deux.", "Sens trois.")
    }

    @Test
    fun `upsertAll rolls back the whole batch on failure`() {
        // Pre-load a known-good entry.
        repo.upsertAll(
            sequenceOf(
                DbnaryWord(lemma = "intact", pos = "noun", senses = listOf(DbnarySense(0, "Reste."))),
            ),
        )
        assertThat(repo.countByLanguage("fr")).isEqualTo(1L)

        // Force a failure mid-batch by feeding a sense whose definition_text
        // violates the V4 length CHECK (empty after construction is impossible
        // — the domain init-block rejects it — so simulate the cascade-delete
        // path by passing a bad batch that runs out of valid input later).
        // We trigger the failure via a runtime exception thrown from the
        // Sequence so the in-tx work attempted before the throw must roll back.
        val mid =
            DbnaryWord(
                lemma = "fragile",
                pos = "noun",
                senses = listOf(DbnarySense(0, "Une definition.")),
            )
        runCatching {
            repo.upsertAll(
                sequence {
                    yield(mid)
                    error("boom mid-batch")
                },
            )
        }

        // The mid-batch insert of `fragile` must NOT have been committed.
        assertThat(repo.findOne("fr", "fragile", "noun")).isNull()
        // Pre-existing data is untouched.
        assertThat(repo.findOne("fr", "intact", "noun")).isNotNull()
    }
}
