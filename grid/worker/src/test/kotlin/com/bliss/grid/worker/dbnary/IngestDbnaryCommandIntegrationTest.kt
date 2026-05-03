package com.bliss.grid.worker.dbnary

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.infrastructure.persistence.JdbcDbnaryRepository
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

/**
 * End-to-end test for `ingest-dbnary` against a real Postgres (ADR-0013 §6).
 * Same Docker-availability gate as the importer integration tests.
 */
class IngestDbnaryCommandIntegrationTest {
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

    @Test
    fun `ingest-dbnary loads lemma plus pos plus pipe-delimited senses and synonyms`() {
        val csv = tempDir.resolve("dbnary.csv")
        Files.write(
            csv,
            """
            lemma,pos,language,definition,synonyms
            voiture,noun,fr,Vehicule a roues.|(Chemin de fer) Wagon de train.,bagnole|automobile
            manger,verb,fr,Action de prendre des aliments.,bouffer|devorer
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestDbnaryCommand().parse(arrayOf("--input", csv.toString()))

        val repo = JdbcDbnaryRepository(requireNotNull(Database.dataSource()))
        assertThat(repo.countByLanguage("fr")).isEqualTo(2L)

        val voiture = repo.findOne("fr", "voiture", "noun")
        assertThat(voiture).isNotNull()
        assertThat(voiture!!.senses.map { it.definitionText })
            .containsExactly("Vehicule a roues.", "(Chemin de fer) Wagon de train.")
        assertThat(voiture.senses.map { it.senseIndex }).containsExactly(0, 1)
        assertThat(voiture.synonyms).containsExactlyInAnyOrder("bagnole", "automobile")

        val manger = repo.findOne("fr", "manger", "verb")
        assertThat(manger).isNotNull()
        assertThat(manger!!.senses.size).isEqualTo(1)
        assertThat(manger.synonyms).containsExactlyInAnyOrder("bouffer", "devorer")
    }

    @Test
    fun `ingest-dbnary skips rows with blank lemma or pos and dedupes the rest`() {
        val csv = tempDir.resolve("dbnary.csv")
        Files.write(
            csv,
            """
            lemma,pos,language,definition,synonyms
            voiture,noun,fr,Premiere version.,
            ,noun,fr,Sans lemme - rejete.,
            chat,,fr,Sans pos - rejete.,
            voiture,noun,fr,Doublon - ignore par dedup.,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestDbnaryCommand().parse(arrayOf("--input", csv.toString()))

        val repo = JdbcDbnaryRepository(requireNotNull(Database.dataSource()))
        assertThat(repo.countByLanguage("fr")).isEqualTo(1L)
        val voiture = repo.findOne("fr", "voiture", "noun")!!
        assertThat(voiture.senses[0].definitionText).isEqualTo("Premiere version.")
    }

    @Test
    fun `ingest-dbnary tolerates rows with empty senses and empty synonyms`() {
        val csv = tempDir.resolve("dbnary.csv")
        Files.write(
            csv,
            """
            lemma,pos,language,definition,synonyms
            elu,noun,fr,,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        IngestDbnaryCommand().parse(arrayOf("--input", csv.toString()))

        val repo = JdbcDbnaryRepository(requireNotNull(Database.dataSource()))
        val elu = repo.findOne("fr", "elu", "noun")
        assertThat(elu).isNotNull()
        assertThat(elu!!.senses.size).isEqualTo(0)
        assertThat(elu.synonyms.size).isEqualTo(0)
    }

    @Test
    fun `ingest-dbnary --truncate clears the language before re-ingesting`() {
        val first = tempDir.resolve("first.csv")
        Files.write(
            first,
            """
            lemma,pos,language,definition,synonyms
            voiture,noun,fr,Sens initial.,
            ancien,adjective,fr,Sens ancien.,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestDbnaryCommand().parse(arrayOf("--input", first.toString()))

        val second = tempDir.resolve("second.csv")
        Files.write(
            second,
            """
            lemma,pos,language,definition,synonyms
            voiture,noun,fr,Sens revise.,bagnole
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestDbnaryCommand().parse(arrayOf("--input", second.toString(), "--truncate"))

        val repo = JdbcDbnaryRepository(requireNotNull(Database.dataSource()))
        assertThat(repo.countByLanguage("fr")).isEqualTo(1L)
        assertThat(repo.findOne("fr", "ancien", "adjective")).isNull()
        assertThat(repo.findOne("fr", "voiture", "noun")!!.senses[0].definitionText)
            .isEqualTo("Sens revise.")
    }

    @Test
    fun `ingest-dbnary keeps non-target language entries when truncating`() {
        val frCsv = tempDir.resolve("fr.csv")
        Files.write(
            frCsv,
            """
            lemma,pos,language,definition,synonyms
            voiture,noun,fr,Vehicule.,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestDbnaryCommand().parse(arrayOf("--input", frCsv.toString()))

        val enCsv = tempDir.resolve("en.csv")
        Files.write(
            enCsv,
            """
            lemma,pos,language,definition,synonyms
            house,noun,en,A dwelling.,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestDbnaryCommand().parse(arrayOf("--input", enCsv.toString(), "--language", "en"))

        // Now truncate fr only.
        val refresh = tempDir.resolve("refresh.csv")
        Files.write(
            refresh,
            """
            lemma,pos,language,definition,synonyms
            chat,noun,fr,Animal domestique.,
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        IngestDbnaryCommand().parse(arrayOf("--input", refresh.toString(), "--truncate"))

        val repo = JdbcDbnaryRepository(requireNotNull(Database.dataSource()))
        assertThat(repo.countByLanguage("fr")).isEqualTo(1L)
        assertThat(repo.countByLanguage("en")).isEqualTo(1L)
        assertThat(repo.findOne("en", "house", "noun")).isNotNull()
        assertThat(repo.findOne("fr", "chat", "noun")).isNotNull()
        assertThat(repo.findOne("fr", "voiture", "noun")).isNull()
    }
}
