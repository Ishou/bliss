package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.grid.domain.lexicon.ClueCandidate
import com.bliss.grid.domain.lexicon.ClueSource
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
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcClueCandidateRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: JdbcClueCandidateRepository

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
        repo = JdbcClueCandidateRepository(dataSource)
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
            conn.createStatement().use {
                it.executeUpdate(
                    "TRUNCATE clue_candidates, dbnary_synonyms, dbnary_senses, " +
                        "dbnary_words, words RESTART IDENTITY CASCADE",
                )
            }
        }
    }

    private fun insertWord(
        word: String,
        language: String = "fr",
        lemma: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
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

    private fun insertDbnaryWordWithSynonyms(
        lemma: String,
        pos: String,
        language: String,
        synonyms: List<String>,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
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
        return id
    }

    @Test
    fun `upsertAll inserts a fresh candidate`() {
        val voiture = insertWord("voiture")
        val report =
            repo.upsertAll(
                sequenceOf(
                    ClueCandidate(
                        wordId = voiture,
                        source = ClueSource.CURATED,
                        clueText = "Bagnole",
                    ),
                ),
            )

        assertThat(report.inserted).isEqualTo(1)
        assertThat(report.updated).isEqualTo(0)
        assertThat(repo.countBySource(ClueSource.CURATED)).isEqualTo(1L)

        val one = repo.findByWord(voiture).single()
        assertThat(one.source).isEqualTo(ClueSource.CURATED)
        assertThat(one.clueText).isEqualTo("Bagnole")
        assertThat(one.senseIndex).isNull()
        assertThat(one.confidence).isNull()
    }

    @Test
    fun `upsertAll updates metadata on an existing key`() {
        val word = insertWord("manger")
        val first =
            ClueCandidate(
                wordId = word,
                source = "mistral-7b-base",
                clueText = "Avaler",
                confidence = 0.5,
                modelVersion = "v0.1",
            )
        repo.upsertAll(sequenceOf(first))
        val second = first.copy(confidence = 0.9, modelVersion = "v0.3")
        val report = repo.upsertAll(sequenceOf(second))

        assertThat(report.inserted).isEqualTo(0)
        assertThat(report.updated).isEqualTo(1)

        val one = repo.findByWord(word).single()
        assertThat(one.confidence).isEqualTo(0.9)
        assertThat(one.modelVersion).isEqualTo("v0.3")
    }

    @Test
    fun `upsertAll lets multiple candidates coexist for one word`() {
        val word = insertWord("voiture")
        repo.upsertAll(
            sequenceOf(
                ClueCandidate(word, ClueSource.CURATED, "Bagnole"),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Automobile"),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Tacot"),
                ClueCandidate(word, "mistral-nemo", "Tas de tôle", confidence = 0.7),
            ),
        )

        val candidates = repo.findByWord(word)
        assertThat(candidates.map { it.clueText })
            .containsExactlyInAnyOrder("Bagnole", "Automobile", "Tacot", "Tas de tôle")
    }

    @Test
    fun `findTopBySourcePriority picks by priority then confidence`() {
        val word = insertWord("voiture")
        repo.upsertAll(
            sequenceOf(
                ClueCandidate(word, "mistral-nemo", "Voiture (modèle)", confidence = 0.4),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Automobile"),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Tacot"),
                ClueCandidate(word, ClueSource.CURATED, "Bagnole"),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Bolide", confidence = 0.9),
            ),
        )

        val priority =
            listOf(ClueSource.CURATED, ClueSource.DBNARY_SYNONYM, "mistral-nemo")
        val top = repo.findTopBySourcePriority(word, priority)!!
        assertThat(top.source).isEqualTo(ClueSource.CURATED)
        assertThat(top.clueText).isEqualTo("Bagnole")

        val withoutCurated =
            repo.findTopBySourcePriority(word, listOf(ClueSource.DBNARY_SYNONYM, "mistral-nemo"))!!
        // Within dbnary-synonym, Bolide has the higher confidence -> wins.
        assertThat(withoutCurated.source).isEqualTo(ClueSource.DBNARY_SYNONYM)
        assertThat(withoutCurated.clueText).isEqualTo("Bolide")
    }

    @Test
    fun `findTopBySourcePriority returns null when no priority source matches`() {
        val word = insertWord("voiture")
        repo.upsertAll(sequenceOf(ClueCandidate(word, "mistral-nemo", "Tacot")))
        val top =
            repo.findTopBySourcePriority(
                word,
                listOf(ClueSource.CURATED, ClueSource.DBNARY_SYNONYM),
            )
        assertThat(top).isNull()
    }

    @Test
    fun `findTopBySourcePriority returns null on empty priority list`() {
        val word = insertWord("voiture")
        repo.upsertAll(sequenceOf(ClueCandidate(word, ClueSource.CURATED, "Bagnole")))
        assertThat(repo.findTopBySourcePriority(word, emptyList())).isNull()
    }

    @Test
    fun `deleteBySource scoped by language only removes target language`() {
        val frWord = insertWord("voiture", language = "fr")
        val enWord = insertWord("car", language = "en")
        repo.upsertAll(
            sequenceOf(
                ClueCandidate(frWord, ClueSource.DBNARY_SYNONYM, "Bagnole"),
                ClueCandidate(enWord, ClueSource.DBNARY_SYNONYM, "Automobile"),
            ),
        )

        val deleted = repo.deleteBySource(ClueSource.DBNARY_SYNONYM, language = "fr")

        assertThat(deleted).isEqualTo(1)
        assertThat(repo.findByWord(frWord)).isEqualTo(emptyList())
        assertThat(repo.findByWord(enWord).map { it.clueText }).containsExactly("Automobile")
    }

    @Test
    fun `deleteBySource without language wipes the source globally`() {
        val frWord = insertWord("voiture", language = "fr")
        val enWord = insertWord("car", language = "en")
        repo.upsertAll(
            sequenceOf(
                ClueCandidate(frWord, "mistral-nemo", "Bagnole"),
                ClueCandidate(enWord, "mistral-nemo", "Auto"),
                ClueCandidate(frWord, ClueSource.CURATED, "Bagnole"),
            ),
        )

        val deleted = repo.deleteBySource("mistral-nemo")

        assertThat(deleted).isEqualTo(2)
        assertThat(repo.countBySource("mistral-nemo")).isEqualTo(0L)
        assertThat(repo.countBySource(ClueSource.CURATED)).isEqualTo(1L)
    }

    @Test
    fun `cascade from words deletes associated candidates`() {
        val word = insertWord("voiture")
        repo.upsertAll(
            sequenceOf(
                ClueCandidate(word, ClueSource.CURATED, "Bagnole"),
                ClueCandidate(word, ClueSource.DBNARY_SYNONYM, "Auto"),
            ),
        )

        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM words WHERE word_id = ?").use { stmt ->
                stmt.setObject(1, word)
                stmt.executeUpdate()
            }
        }

        assertThat(repo.findByWord(word)).isEqualTo(emptyList())
        assertThat(repo.countBySource(ClueSource.CURATED)).isEqualTo(0L)
    }

    @Test
    fun `deriveSynonymClues emits one capitalised candidate per synonym`() {
        val voiture = insertWord("voiture")
        insertDbnaryWordWithSynonyms(
            lemma = "voiture",
            pos = "noun",
            language = "fr",
            synonyms = listOf("automobile", "tas de tôle", "bagnole"),
        )

        val inserted = repo.deriveSynonymClues("fr")

        assertThat(inserted).isEqualTo(3)
        val candidates = repo.findByWord(voiture)
        assertThat(candidates.map { it.clueText })
            .containsExactlyInAnyOrder("Automobile", "Tas de tôle", "Bagnole")
        candidates.forEach { c ->
            assertThat(c.source).isEqualTo(ClueSource.DBNARY_SYNONYM)
            assertThat(c.senseIndex).isNull()
            assertThat(c.confidence).isNull()
        }
    }

    @Test
    fun `deriveSynonymClues filters out self-references and oversize synonyms`() {
        val voiture = insertWord("voiture", lemma = "voiture")
        insertDbnaryWordWithSynonyms(
            lemma = "voiture",
            pos = "noun",
            language = "fr",
            synonyms =
                listOf(
                    "voiture", // exact-form self-reference
                    "VOITURE", // case-insensitive self-reference
                    "a".repeat(81), // exceeds V6 length CHECK
                    "Bolide", // valid
                ),
        )

        val inserted = repo.deriveSynonymClues("fr")

        assertThat(inserted).isEqualTo(1)
        assertThat(repo.findByWord(voiture).map { it.clueText }).containsExactly("Bolide")
    }

    @Test
    fun `deriveSynonymClues only joins on the requested language`() {
        val frWord = insertWord("chat", language = "fr")
        val enWord = insertWord("cat", language = "en")
        insertDbnaryWordWithSynonyms("chat", "noun", "fr", listOf("matou"))
        insertDbnaryWordWithSynonyms("cat", "noun", "en", listOf("feline"))

        val inserted = repo.deriveSynonymClues("fr")

        assertThat(inserted).isEqualTo(1)
        assertThat(repo.findByWord(frWord).map { it.clueText }).containsExactly("Matou")
        assertThat(repo.findByWord(enWord)).isEqualTo(emptyList())
    }

    @Test
    fun `deriveSynonymClues is idempotent thanks to NULLS NOT DISTINCT`() {
        val voiture = insertWord("voiture")
        insertDbnaryWordWithSynonyms("voiture", "noun", "fr", listOf("bagnole"))

        val first = repo.deriveSynonymClues("fr")
        val second = repo.deriveSynonymClues("fr")

        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(0) // ON CONFLICT DO NOTHING
        assertThat(repo.findByWord(voiture).size).isEqualTo(1)
    }

    @Test
    fun `deriveSynonymClues filters synonym that matches lemma but not word`() {
        val voitures = insertWord("voitures", lemma = "voiture")
        insertDbnaryWordWithSynonyms("voiture", "noun", "fr", listOf("voiture", "carrosse"))

        val inserted = repo.deriveSynonymClues("fr")

        assertThat(inserted).isEqualTo(1)
        assertThat(repo.findByWord(voitures).map { it.clueText }).containsExactly("Carrosse")
    }

    @Test
    fun `findLemmaWordIds maps each lemma to its words word_id`() {
        val voiture = insertWord("voiture")
        val maison = insertWord("maison")
        val absent = "doesnotexist"

        val map = repo.findLemmaWordIds("fr", listOf("voiture", "maison", absent))

        assertThat(map.size).isEqualTo(2)
        assertThat(map["voiture"]).isEqualTo(voiture)
        assertThat(map["maison"]).isEqualTo(maison)
        assertThat(map.containsKey(absent)).isEqualTo(false)
    }

    @Test
    fun `findLemmaWordIds is language-scoped`() {
        val frVoiture = insertWord("voiture", language = "fr")
        insertWord("voiture", language = "en") // different row, should NOT match fr query

        val map = repo.findLemmaWordIds("fr", listOf("voiture"))

        assertThat(map.size).isEqualTo(1)
        assertThat(map["voiture"]).isEqualTo(frVoiture)
    }

    @Test
    fun `findLemmaWordIds returns empty map on empty input`() {
        insertWord("voiture")
        assertThat(repo.findLemmaWordIds("fr", emptyList())).isEqualTo(emptyMap())
    }

    @Test
    fun `upsertAll rolls back the whole batch on failure`() {
        val word = insertWord("voiture")
        repo.upsertAll(
            sequenceOf(ClueCandidate(word, ClueSource.CURATED, "Original")),
        )
        runCatching {
            repo.upsertAll(
                sequence {
                    yield(ClueCandidate(word, "mistral-nemo", "Should rollback"))
                    error("boom mid-batch")
                },
            )
        }

        // The mid-batch insert must not have been committed.
        assertThat(repo.countBySource("mistral-nemo")).isEqualTo(0L)
        // Pre-existing data is intact.
        assertThat(repo.countBySource(ClueSource.CURATED)).isEqualTo(1L)
    }
}
