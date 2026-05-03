package com.bliss.grid.worker.clues

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.grid.domain.lexicon.ClueSource
import com.bliss.grid.infrastructure.persistence.JdbcClueCandidateRepository
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class IngestClueCandidatesCommandIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

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
                    stmt.setString(6, word)
                    stmt.executeUpdate()
                }
        }
        return id
    }

    @Test
    fun `ingest-clue-candidates loads CSV rows and joins on lemma to find word_id`() {
        val voiture = insertWord("voiture")
        val maison = insertWord("maison")

        val csv = tempDir.resolve("clues.csv")
        Files.write(
            csv,
            """
            lemma,clue_text,source,model_version,confidence
            voiture,Bagnole,mistral-nemo,mistral-nemo:latest,
            maison,Habitation,mistral-nemo,mistral-nemo:latest,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestClueCandidatesCommand().parse(arrayOf("--input", csv.toString()))

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.countBySource("mistral-nemo")).isEqualTo(2L)

        val voitureClue = repo.findByWord(voiture).single()
        assertThat(voitureClue.clueText).isEqualTo("Bagnole")
        assertThat(voitureClue.source).isEqualTo("mistral-nemo")
        assertThat(voitureClue.modelVersion).isEqualTo("mistral-nemo:latest")

        assertThat(repo.findByWord(maison).single().clueText).isEqualTo("Habitation")
    }

    @Test
    fun `ingest-clue-candidates skips rows whose lemma is not in words`() {
        val voiture = insertWord("voiture")
        // No row for "absent" — its CSV line should be silently dropped.

        val csv = tempDir.resolve("partial.csv")
        Files.write(
            csv,
            """
            lemma,clue_text,source
            voiture,Bagnole,mistral-nemo
            absent,Should not insert,mistral-nemo
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestClueCandidatesCommand().parse(arrayOf("--input", csv.toString()))

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.countBySource("mistral-nemo")).isEqualTo(1L)
        assertThat(repo.findByWord(voiture).single().clueText).isEqualTo("Bagnole")
    }

    @Test
    fun `ingest-clue-candidates --source overrides CSV source column`() {
        val voiture = insertWord("voiture")

        val csv = tempDir.resolve("override.csv")
        Files.write(
            csv,
            """
            lemma,clue_text,source
            voiture,Bagnole,whatever
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestClueCandidatesCommand().parse(
            arrayOf("--input", csv.toString(), "--source", "mistral-nemo:latest"),
        )

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        val candidate = repo.findByWord(voiture).single()
        assertThat(candidate.source).isEqualTo("mistral-nemo:latest")
        assertThat(repo.countBySource("mistral-nemo:latest")).isEqualTo(1L)
        assertThat(repo.countBySource("whatever")).isEqualTo(0L)
    }

    @Test
    fun `ingest-clue-candidates --truncate clears existing source rows`() {
        val voiture = insertWord("voiture")

        val first = tempDir.resolve("first.csv")
        Files.write(
            first,
            """
            lemma,clue_text,source
            voiture,Old clue,mistral-nemo
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestClueCandidatesCommand().parse(arrayOf("--input", first.toString()))

        val second = tempDir.resolve("second.csv")
        Files.write(
            second,
            """
            lemma,clue_text,source
            voiture,New clue,mistral-nemo
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestClueCandidatesCommand().parse(arrayOf("--input", second.toString(), "--truncate"))

        val repo = JdbcClueCandidateRepository(Database.dataSource()!!)
        assertThat(repo.findByWord(voiture).map { it.clueText }).containsExactly("New clue")
    }

    @Test
    fun `ingest-clue-candidates leaves other sources alone when truncating`() {
        val voiture = insertWord("voiture")
        val seedRepo = JdbcClueCandidateRepository(Database.dataSource()!!)
        seedRepo.upsertAll(
            sequenceOf(
                com.bliss.grid.domain.lexicon.ClueCandidate(
                    voiture,
                    ClueSource.DBNARY_SYNONYM,
                    "Bagnole",
                ),
            ),
        )

        val csv = tempDir.resolve("scoped.csv")
        Files.write(
            csv,
            """
            lemma,clue_text,source
            voiture,Generated by mistral,mistral-nemo
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestClueCandidatesCommand().parse(arrayOf("--input", csv.toString(), "--truncate"))

        val candidates = seedRepo.findByWord(voiture)
        assertThat(candidates.map { it.source })
            .containsExactlyInAnyOrder(ClueSource.DBNARY_SYNONYM, "mistral-nemo")
        assertThat(seedRepo.countBySource(ClueSource.DBNARY_SYNONYM)).isEqualTo(1L)
        assertThat(seedRepo.countBySource("mistral-nemo")).isEqualTo(1L)
    }

    @Test
    fun `ingest-clue-candidates --truncate refuses heterogeneous sources in the CSV`() {
        insertWord("voiture")
        insertWord("maison")
        val csv = tempDir.resolve("mixed.csv")
        Files.write(
            csv,
            """
            lemma,clue_text,source
            voiture,Bagnole,mistral-nemo
            maison,Habitation,curated
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        val ex =
            try {
                IngestClueCandidatesCommand().parse(
                    arrayOf("--input", csv.toString(), "--truncate"),
                )
                null
            } catch (e: IllegalArgumentException) {
                e
            }
        assertThat(ex).isNotNull()
    }
}
