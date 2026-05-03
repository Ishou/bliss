package com.bliss.grid.worker.exporter

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsNone
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.bliss.grid.domain.lexicon.PercentileLengthFilterConfig
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.apache.commons.csv.CSVFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * End-to-end test for `export-words` against a real Postgres (ADR-0013 §7, §8).
 * Docker-availability gate matches PR83/PR84/PR85 worker integration tests.
 */
class ExportWordsCommandIntegrationTest {
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
    fun `export-words emits only fr rows with non-null clues, sorted by word, with header`() {
        seedFixtures()

        val output = tempDir.resolve("words-fr.csv")
        passthroughCommand().parse(
            arrayOf("--language", "fr", "--output", output.toString(), "--curated-dir", emptyCuratedDir().toString()),
        )

        val lines = Files.readAllLines(output, StandardCharsets.UTF_8)
        // 1 header + 5 fr rows with clues (the 6th fr row has NULL clue and the en row is filtered by language).
        assertThat(lines.size).isEqualTo(6)
        assertThat(lines[0]).isEqualTo("word,language,length,frequency,difficulty,clue,source,source_license,lemma")

        val records = parseCsv(output)
        // Sorted by word ascending.
        assertThat(records.map { it.word }).containsExactly("aide", "ami", "chat", "rose", "soleil")
        // Round-trip: each non-null field round-trips intact.
        val chat = records.first { it.word == "chat" }
        assertThat(chat.language).isEqualTo("fr")
        assertThat(chat.length).isEqualTo(4)
        assertThat(chat.clue).isEqualTo("Felin domestique")
        assertThat(chat.source).isEqualTo("hand-curated")
        assertThat(chat.sourceLicense).isEqualTo("FSL-1.1-MIT")
        // Frequency round-trips as integer string (REAL stored as 5000.0 → exported as "5000").
        assertThat(chat.frequency).isEqualTo("5000")
        // Difficulty was NULL on insert → empty string in CSV.
        assertThat(chat.difficulty).isEqualTo("")
        // Difficulty was non-null on the "rose" fixture → round-trips to its string form.
        val rose = records.first { it.word == "rose" }
        assertThat(rose.difficulty).isEqualTo("0.42")
    }

    @Test
    fun `export-words is byte-identical across re-runs against the same DB state`() {
        seedFixtures()

        val first = tempDir.resolve("first.csv")
        val second = tempDir.resolve("second.csv")
        val curated = emptyCuratedDir()
        passthroughCommand().parse(arrayOf("--language", "fr", "--output", first.toString(), "--curated-dir", curated.toString()))
        passthroughCommand().parse(arrayOf("--language", "fr", "--output", second.toString(), "--curated-dir", curated.toString()))

        val firstBytes = Files.readAllBytes(first)
        val secondBytes = Files.readAllBytes(second)
        assertThat(firstBytes.contentEquals(secondBytes)).isTrue()
    }

    @Test
    fun `export-words with --include-clueless emits rows with null clue`() {
        seedFixtures()

        val output = tempDir.resolve("words-clueless.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--include-clueless",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
            ),
        )

        val records = parseCsv(output)
        // 5 fr rows with clues + 1 fr row with NULL clue (zzznoclue).
        assertThat(records.size).isEqualTo(6)
        assertThat(records.any { it.word == "zzznoclue" }).isTrue()
        val noclueLine = records.first { it.word == "zzznoclue" }
        assertThat(noclueLine.clue).isEqualTo("")
    }

    @Test
    fun `export-words with --placeholder-clue-from-word emits word as clue for null-clue rows`() {
        seedFixtures()

        val output = tempDir.resolve("words-placeholder.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--placeholder-clue-from-word",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
            ),
        )

        val records = parseCsv(output)
        // All 6 fr rows — every row now has a non-blank clue (either real or word-as-placeholder).
        assertThat(records.size).isEqualTo(6)
        val noclueLine = records.first { it.word == "zzznoclue" }
        assertThat(noclueLine.clue).isEqualTo("zzznoclue")
    }

    @Test
    fun `export-words propagates lemma clue to inflected forms via COALESCE`() {
        seedLemmaFixtures()

        val output = tempDir.resolve("words-lemma.csv")
        passthroughCommand().parse(
            arrayOf("--language", "fr", "--output", output.toString(), "--curated-dir", emptyCuratedDir().toString()),
        )

        val records = parseCsv(output)
        // Both "aimer" (own clue) and "aimera" (inherited via lemma JOIN) appear.
        assertThat(records.size).isEqualTo(2)
        val aimer = records.first { it.word == "aimer" }
        assertThat(aimer.clue).isEqualTo("Eprouver de l'amour")
        val aimera = records.first { it.word == "aimera" }
        assertThat(aimera.clue).isEqualTo("Eprouver de l'amour")
    }

    @Test
    fun `export-words merges curated rows and applies per-length percentile filter`() {
        // Length 2: ratio 0.0 → curated-only. Length 3: ratio 0.4. Length 4: ratio 0.5.
        // Two grammalecte rows per length so the math is unambiguous (drop bottom one).
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word, language, clue, frequency, source, source_license)
                    VALUES (?, 'fr', ?, ?, 'grammalecte', 'MPL-2.0')
                    """.trimIndent(),
                ).use { stmt ->
                    listOf(
                        Triple("ck", "noise", 100f),
                        Triple("ab", "noise", 200f),
                        Triple("xyz", "noise", 100f),
                        Triple("abc", "noise", 200f),
                        Triple("abcd", "noise", 100f),
                        Triple("efgh", "noise", 200f),
                    ).forEach { (w, c, f) ->
                        stmt.setString(1, w)
                        stmt.setString(2, c)
                        stmt.setFloat(3, f)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
        val curatedDir = Files.createDirectory(tempDir.resolve("curated"))
        Files.writeString(
            curatedDir.resolve("fr.csv"),
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ne,fr,2,100000,,Cardinal,bliss,CC0-1.0
            mr,fr,2,100000,,Monsieur,bliss,CC0-1.0
            """.trimIndent() + "\n",
        )

        val output = tempDir.resolve("merged.csv")
        ExportWordsCommand(
            percentileConfig =
                PercentileLengthFilterConfig(
                    keepRatioByLength = mapOf(2 to 0.0, 3 to 0.4),
                    defaultKeepRatio = 0.5,
                ),
        ).parse(arrayOf("--language", "fr", "--output", output.toString(), "--curated-dir", curatedDir.toString()))

        val words = parseCsv(output).map { it.word }
        // Length 2: only curated survive (`ne`, `mr`). Grammalecte `ck`, `ab` dropped.
        assertThat(words).contains("ne")
        assertThat(words).contains("mr")
        assertThat(words).containsNone("ck", "ab")
        // Length 3: keep top 40% of 2 → drop floor(2 * 0.6)=1 → keep 1, the higher freq.
        assertThat(words).contains("abc")
        assertThat(words).containsNone("xyz")
        // Length 4: keep top 50% of 2 → drop 1, keep 1.
        assertThat(words).contains("efgh")
        assertThat(words).containsNone("abcd")
    }

    @Test
    fun `curated row overrides a grammalecte row with the same word`() {
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO words (word, language, clue, frequency, source, source_license) " +
                        "VALUES ('ne', 'fr', 'grammalecte clue', 999999, 'grammalecte', 'MPL-2.0')",
                ).use { it.executeUpdate() }
        }
        val curatedDir = Files.createDirectory(tempDir.resolve("curated"))
        Files.writeString(
            curatedDir.resolve("fr.csv"),
            "word,language,length,frequency,difficulty,clue,source,source_license\n" +
                "ne,fr,2,100000,,Curated clue,bliss,CC0-1.0\n",
        )

        val output = tempDir.resolve("override.csv")
        passthroughCommand().parse(
            arrayOf("--language", "fr", "--output", output.toString(), "--curated-dir", curatedDir.toString()),
        )

        val rows = parseCsv(output).filter { it.word == "ne" }
        assertThat(rows.size).isEqualTo(1)
        assertThat(rows[0].source).isEqualTo("bliss")
        assertThat(rows[0].clue).isEqualTo("Curated clue")
    }

    @Test
    fun `clue_candidates dbnary-synonym overrides words clue when in priority list`() {
        val voitureId =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = "old legacy clue",
                source = "grammalecte",
                license = "MPL-2.0",
            )
        insertCandidate(voitureId, source = "dbnary-synonym", clueText = "Bagnole")

        val output = tempDir.resolve("dbnary.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                "--candidate-priority",
                "curated,dbnary-synonym",
            ),
        )

        val row = parseCsv(output).single { it.word == "voiture" }
        assertThat(row.clue).isEqualTo("Bagnole")
        assertThat(row.source).isEqualTo("dbnary-synonym")
        assertThat(row.sourceLicense).isEqualTo("CC-BY-SA-4.0")
    }

    @Test
    fun `clue_candidates curated wins over dbnary-synonym for the same word`() {
        val voitureId =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = "old legacy clue",
                source = "grammalecte",
                license = "MPL-2.0",
            )
        insertCandidate(voitureId, source = "dbnary-synonym", clueText = "Bagnole")
        insertCandidate(voitureId, source = "curated", clueText = "Quatre roues motorisees")

        val output = tempDir.resolve("priority.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                "--candidate-priority",
                "curated,dbnary-synonym",
            ),
        )

        val row = parseCsv(output).single { it.word == "voiture" }
        assertThat(row.clue).isEqualTo("Quatre roues motorisees")
        assertThat(row.source).isEqualTo("curated")
        assertThat(row.sourceLicense).isEqualTo("CC0-1.0")
    }

    @Test
    fun `empty candidate priority preserves legacy words clue`() {
        val voitureId =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = "old legacy clue",
                source = "grammalecte",
                license = "MPL-2.0",
            )
        insertCandidate(voitureId, source = "dbnary-synonym", clueText = "Bagnole")

        val output = tempDir.resolve("empty-priority.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                "--candidate-priority",
                "",
            ),
        )

        val row = parseCsv(output).single { it.word == "voiture" }
        assertThat(row.clue).isEqualTo("old legacy clue")
        assertThat(row.source).isEqualTo("grammalecte")
        assertThat(row.sourceLicense).isEqualTo("MPL-2.0")
    }

    @Test
    fun `clue_candidates on a lemma propagates to inflected forms via the lemma JOIN`() {
        // The lemma "voiture" has a `mistral-nemo` candidate; "voitures" (plural,
        // lemma="voiture") has none of its own. The export must inherit the
        // lemma's candidate via the LATERAL's lemma branch.
        val voitureLemma =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = "lemma legacy clue",
                source = "grammalecte",
                license = "MPL-2.0",
                lemma = "voiture",
            )
        insertWordReturningId(
            word = "voitures",
            language = "fr",
            clue = null,
            source = "grammalecte",
            license = "MPL-2.0",
            lemma = "voiture",
        )
        insertCandidate(voitureLemma, source = "mistral-nemo", clueText = "Bagnole")

        val output = tempDir.resolve("propagation.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--include-clueless",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                "--candidate-priority",
                "mistral-nemo",
            ),
        )

        val rows = parseCsv(output)
        val voitures = rows.single { it.word == "voitures" }
        assertThat(voitures.clue).isEqualTo("Bagnole")
        assertThat(voitures.source).isEqualTo("mistral-nemo")
        // Lemma row gets the same candidate too.
        val voiture = rows.single { it.word == "voiture" }
        assertThat(voiture.clue).isEqualTo("Bagnole")
        assertThat(voiture.source).isEqualTo("mistral-nemo")
    }

    @Test
    fun `clue_candidates targeting the exact word wins over the lemma's candidate`() {
        // Both lemma and inflected form have candidates for the same source.
        // The exact-word match must win on the inflected form's row.
        val voitureLemma =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = null,
                source = "grammalecte",
                license = "MPL-2.0",
                lemma = "voiture",
            )
        val voitures =
            insertWordReturningId(
                word = "voitures",
                language = "fr",
                clue = null,
                source = "grammalecte",
                license = "MPL-2.0",
                lemma = "voiture",
            )
        insertCandidate(voitureLemma, source = "mistral-nemo", clueText = "Bagnole")
        insertCandidate(voitures, source = "mistral-nemo", clueText = "Bagnoles")

        val output = tempDir.resolve("exact-win.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--include-clueless",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                "--candidate-priority",
                "mistral-nemo",
            ),
        )

        val rows = parseCsv(output)
        // Inflected word picks its own candidate, not the lemma's.
        assertThat(rows.single { it.word == "voitures" }.clue).isEqualTo("Bagnoles")
        assertThat(rows.single { it.word == "voiture" }.clue).isEqualTo("Bagnole")
    }

    @Test
    fun `priority list excludes a source when not listed`() {
        val voitureId =
            insertWordReturningId(
                word = "voiture",
                language = "fr",
                clue = "legacy",
                source = "grammalecte",
                license = "MPL-2.0",
            )
        insertCandidate(voitureId, source = "dbnary-synonym", clueText = "Bagnole")

        val output = tempDir.resolve("only-curated.csv")
        passthroughCommand().parse(
            arrayOf(
                "--language",
                "fr",
                "--output",
                output.toString(),
                "--curated-dir",
                emptyCuratedDir().toString(),
                // dbnary-synonym is NOT in the priority list — it must not win.
                "--candidate-priority",
                "curated",
            ),
        )

        val row = parseCsv(output).single { it.word == "voiture" }
        assertThat(row.clue).isEqualTo("legacy")
        assertThat(row.source).isEqualTo("grammalecte")
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /** Build a command that disables percentile filtering — most tests assert raw fixture content. */
    private fun passthroughCommand(): ExportWordsCommand =
        ExportWordsCommand(
            percentileConfig =
                PercentileLengthFilterConfig(
                    keepRatioByLength = emptyMap(),
                    defaultKeepRatio = 1.0,
                ),
        )

    /** Curated dir that exists but holds no `<lang>.csv` — keeps tests isolated from `data/curated`. */
    private fun emptyCuratedDir(): Path = Files.createDirectories(tempDir.resolve("empty-curated"))

    private fun insertWordReturningId(
        word: String,
        language: String,
        clue: String?,
        source: String,
        license: String,
        lemma: String? = null,
    ): java.util.UUID {
        val id = java.util.UUID.randomUUID()
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word_id, word, language, clue, source, source_license, lemma)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, word)
                    stmt.setString(3, language)
                    if (clue == null) stmt.setNull(4, java.sql.Types.VARCHAR) else stmt.setString(4, clue)
                    stmt.setString(5, source)
                    stmt.setString(6, license)
                    if (lemma == null) stmt.setNull(7, java.sql.Types.VARCHAR) else stmt.setString(7, lemma)
                    stmt.executeUpdate()
                }
        }
        return id
    }

    private fun insertCandidate(
        wordId: java.util.UUID,
        source: String,
        clueText: String,
    ) {
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO clue_candidates (word_id, source, clue_text)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, wordId)
                    stmt.setString(2, source)
                    stmt.setString(3, clueText)
                    stmt.executeUpdate()
                }
        }
    }

    private fun seedFixtures() {
        // 5 fr rows with clues (varied difficulty: 4 NULL, 1 non-null), 1 fr row with NULL clue
        // (filtered out), 1 en row with a clue (filtered by language).
        // "chat" carries a non-null frequency so the frequency round-trip can be asserted.
        val fixtures =
            listOf(
                Fixture("chat", "fr", null, "Felin domestique", frequency = 5000f),
                Fixture("ami", "fr", null, "Compagnon proche"),
                Fixture("aide", "fr", null, "Soutien apporte"),
                Fixture("soleil", "fr", null, "Astre du jour"),
                Fixture("rose", "fr", 0.42f, "Fleur a epines"),
                Fixture("zzznoclue", "fr", null, null),
                Fixture("cat", "en", null, "Domestic feline"),
            )
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word, language, difficulty, clue, frequency, source, source_license)
                    VALUES (?, ?, ?, ?, ?, 'hand-curated', 'FSL-1.1-MIT')
                    """.trimIndent(),
                ).use { stmt ->
                    for (f in fixtures) {
                        stmt.setString(1, f.word)
                        stmt.setString(2, f.language)
                        if (f.difficulty == null) stmt.setNull(3, java.sql.Types.REAL) else stmt.setFloat(3, f.difficulty)
                        if (f.clue == null) stmt.setNull(4, java.sql.Types.VARCHAR) else stmt.setString(4, f.clue)
                        if (f.frequency == null) stmt.setNull(5, java.sql.Types.REAL) else stmt.setFloat(5, f.frequency)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
    }

    private fun seedLemmaFixtures() {
        // "aimer" is the lemma with its own clue.
        // "aimera" is an inflected form with no clue; it points to lemma "aimer".
        // The export LEFT JOIN should propagate "aimer"'s clue to "aimera" via COALESCE.
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word, language, lemma, clue, source, source_license)
                    VALUES (?, 'fr', ?, ?, 'test', 'test')
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, "aimer")
                    stmt.setString(2, "aimer")
                    stmt.setString(3, "Eprouver de l'amour")
                    stmt.addBatch()
                    stmt.setString(1, "aimera")
                    stmt.setString(2, "aimer")
                    stmt.setNull(3, java.sql.Types.VARCHAR)
                    stmt.addBatch()
                    stmt.executeBatch()
                }
        }
    }

    private fun ds(): DataSource = requireNotNull(Database.dataSource())

    private fun parseCsv(path: Path): List<CsvRow> =
        Files.newInputStream(path).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val format =
                    CSVFormat.RFC4180
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
                org.apache.commons.csv.CSVParser.parse(reader, format).use { parser ->
                    parser.records.map { rec ->
                        CsvRow(
                            word = rec.get("word"),
                            language = rec.get("language"),
                            length = rec.get("length").toInt(),
                            frequency = rec.get("frequency"),
                            difficulty = rec.get("difficulty"),
                            clue = rec.get("clue"),
                            source = rec.get("source"),
                            sourceLicense = rec.get("source_license"),
                            lemma = rec.get("lemma"),
                        )
                    }
                }
            }
        }

    private data class Fixture(
        val word: String,
        val language: String,
        val difficulty: Float?,
        val clue: String?,
        val frequency: Float? = null,
    )

    private data class CsvRow(
        val word: String,
        val language: String,
        val length: Int,
        val frequency: String,
        val difficulty: String,
        val clue: String,
        val source: String,
        val sourceLicense: String,
        val lemma: String,
    )
}
