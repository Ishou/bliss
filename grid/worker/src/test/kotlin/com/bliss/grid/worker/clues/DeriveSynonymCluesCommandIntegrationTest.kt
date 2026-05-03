package com.bliss.grid.worker.clues

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.lexicon.ClueSource
import com.bliss.grid.infrastructure.persistence.JdbcClueCandidateRepository
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * End-to-end test for `derive-synonym-clues` against a real Postgres
 * (ADR-0013 §6). Same Docker-availability gate as the other worker
 * integration tests.
 */
class DeriveSynonymCluesCommandIntegrationTest {
    private lateinit var pg: PostgreSQLContainer<*>

    @BeforeEach
    fun setUp() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        System.setProperty(
            "DATABASE_URL",
            "jdbc:postgresql://${pg.host}:${pg.firstMappedPort}/${pg.databaseName}" +
                "?user=${pg.username}&password=${pg.password}",
        )
        Database.start()
    }

    @AfterEach
    fun tearDown() {
        Database.stopForTesting()
        System.clearProperty("DATABASE_URL")
        if (::pg.isInitialized) pg.stop()
    }

    private fun insertWord(
        word: String,
        language: String = "fr",
        lemma: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        Database.dataSource()!!.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word_id, word, language, source, source_license, lemma)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, word)
                    stmt.setString(3, language)
                    stmt.setString(4, "test")
                    stmt.setString(5, "MPL-2.0")
                    stmt.setString(6, lemma ?: word)
                    stmt.executeUpdate()
                }
        }
        return id
    }

    private fun insertDbnaryEntry(
        lemma: String,
        pos: String,
        language: String,
        synonyms: List<String>,
    ) {
        val id = UUID.randomUUID()
        Database.dataSource()!!.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO dbnary_words (id, lemma, pos, language) VALUES (?, ?, ?, ?)",
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, lemma)
                    stmt.setString(3, pos)
                    stmt.setString(4, language)
                    stmt.executeUpdate()
                }
            conn
                .prepareStatement(
                    "INSERT INTO dbnary_synonyms (dbnary_word_id, synonym_lemma) VALUES (?, ?)",
                ).use { stmt ->
                    for (syn in synonyms) {
                        stmt.setObject(1, id)
                        stmt.setString(2, syn)
                        stmt.executeUpdate()
                    }
                }
        }
    }

    @Test
    fun `derive-synonym-clues populates clue_candidates from the synonym graph`() {
        val voiture = insertWord("voiture")
        val maison = insertWord("maison")
        insertDbnaryEntry("voiture", "noun", "fr", listOf("bagnole", "automobile"))
        insertDbnaryEntry("maison", "noun", "fr", listOf("habitation"))

        DeriveSynonymCluesCommand().parse(emptyArray())

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.countBySource(ClueSource.DBNARY_SYNONYM)).isEqualTo(3L)
        assertThat(repo.findByWord(voiture).map { it.clueText })
            .containsExactlyInAnyOrder("Bagnole", "Automobile")
        assertThat(repo.findByWord(maison).map { it.clueText }).containsExactly("Habitation")
    }

    @Test
    fun `derive-synonym-clues is additive on re-run without truncate`() {
        val voiture = insertWord("voiture")
        insertDbnaryEntry("voiture", "noun", "fr", listOf("bagnole"))

        DeriveSynonymCluesCommand().parse(emptyArray())

        // After the first run, ingest a new synonym and re-derive.
        Database.dataSource()!!.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO dbnary_synonyms (dbnary_word_id, synonym_lemma)
                    SELECT id, 'tacot' FROM dbnary_words WHERE lemma = 'voiture'
                    """.trimIndent(),
                ).use { it.executeUpdate() }
        }
        DeriveSynonymCluesCommand().parse(emptyArray())

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.findByWord(voiture).map { it.clueText })
            .containsExactlyInAnyOrder("Bagnole", "Tacot")
    }

    @Test
    fun `derive-synonym-clues --truncate clears the language before re-deriving`() {
        val voiture = insertWord("voiture")
        insertDbnaryEntry("voiture", "noun", "fr", listOf("bagnole", "obsolete"))

        DeriveSynonymCluesCommand().parse(emptyArray())

        // Drop the obsolete synonym from DBnary and re-run with truncate.
        Database.dataSource()!!.connection.use { conn ->
            conn.prepareStatement("DELETE FROM dbnary_synonyms WHERE synonym_lemma = 'obsolete'").use {
                it.executeUpdate()
            }
        }
        DeriveSynonymCluesCommand().parse(arrayOf("--truncate"))

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.findByWord(voiture).map { it.clueText }).containsExactly("Bagnole")
    }

    @Test
    fun `derive-synonym-clues respects --language scope`() {
        val frWord = insertWord("chat", language = "fr")
        val enWord = insertWord("cat", language = "en")
        insertDbnaryEntry("chat", "noun", "fr", listOf("matou"))
        insertDbnaryEntry("cat", "noun", "en", listOf("feline"))

        // Default language=fr only derives French candidates.
        DeriveSynonymCluesCommand().parse(emptyArray())

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.findByWord(frWord).map { it.clueText }).containsExactly("Matou")
        assertThat(repo.findByWord(enWord).map { it.clueText }).isEqualTo(emptyList())

        // Now derive English explicitly.
        DeriveSynonymCluesCommand().parse(arrayOf("--language", "en"))
        assertThat(repo.findByWord(enWord).map { it.clueText }).containsExactly("Feline")
        assertThat(repo.findByWord(frWord).map { it.clueText }).containsExactly("Matou")
    }
}
