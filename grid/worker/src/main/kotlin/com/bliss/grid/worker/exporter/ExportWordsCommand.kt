// `bliss-worker export-words` — DB → CSV exporter (ADR-0013 §7, §8).
package com.bliss.grid.worker.exporter

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * Exports the locally-reviewed `words` table to a committed CSV (ADR-0013 §8).
 *
 * The CSV is the production source of truth for `grid-api`; the DB is a
 * local-dev scratch space for the `import-words` → `generate-clues` →
 * review → `export-words` pipeline.
 *
 * Output columns mirror the persisted columns of the `words` table that are
 * relevant to the API (no DB-internal `id` / `word_id` / `created_at`):
 * `word, language, length, difficulty, clue, source, source_license`.
 *
 * Sorted by `(language, word)` for stable git diffs. Idempotent — re-running
 * on the same DB state produces a byte-identical file.
 *
 * Only rows where `clue IS NOT NULL` are emitted (the API requires a clue
 * per word; rows without clues are still WIP in the local pipeline).
 */
class ExportWordsCommand : CliktCommand(name = "export-words") {
    private val log = LoggerFactory.getLogger(ExportWordsCommand::class.java)

    private val language by option("--language", help = "ISO language code").default("fr")

    /**
     * Default targets the api module's classpath resource. The path is
     * computed at run time using the resolved `--language` value, so
     * `--language en` writes `words-en.csv` next to `words-fr.csv`.
     * Override with `--output` for ad-hoc exports.
     */
    private val output by option("--output", help = "Destination CSV path")
        .path(canBeDir = false)

    /**
     * When set, drops the `clue IS NOT NULL` filter and emits every row of the language —
     * useful when shipping a corpus snapshot that the clue pipeline will fill in later.
     * The grid-api's CSV reader skips rows without a clue, so a clueless export keeps
     * the file in sync with the DB without breaking the API.
     */
    private val includeClueless by option(
        "--include-clueless",
        help = "Include rows whose clue is still NULL (default: clued rows only)",
    ).flag()

    /**
     * Transitional knob: when no real clue exists (own or lemma's), fall back to the word
     * itself. Lets grid-api load and exercise grid generation before the real clue pipeline
     * has run. Implies --include-clueless because every row now ships with a non-blank clue.
     */
    private val placeholderClueFromWord by option(
        "--placeholder-clue-from-word",
        help = "When clue is NULL, emit the word as its own clue (transitional)",
    ).flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for export-words")
        executeExport(ds, output ?: defaultOutputPath(language))
    }

    /** Test seam: integration test wires its own [DataSource] and output path. */
    internal fun executeExport(
        ds: DataSource,
        outputPath: Path,
    ) {
        MDC.put("correlation_id", UUID.randomUUID().toString())
        try {
            log.info(
                "export_words_start language={} include_clueless={} placeholder_clue_from_word={} output={}",
                language,
                includeClueless,
                placeholderClueFromWord,
                outputPath,
            )
            outputPath.parent?.let { Files.createDirectories(it) }

            // --placeholder-clue-from-word implies emitting every row (the word itself is the
            // fallback clue), so it pulls in the all-rows SELECT regardless of --include-clueless.
            val sql =
                when {
                    placeholderClueFromWord -> SELECT_ALL_PLACEHOLDER_SQL
                    includeClueless -> SELECT_ALL_SQL
                    else -> SELECT_CLUED_SQL
                }
            val rowCount =
                ds.connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, language)
                        stmt.executeQuery().use { rs ->
                            Files.newOutputStream(outputPath).use { out ->
                                OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                                    CSVPrinter(writer, csvFormat()).use { printer ->
                                        var rows = 0
                                        while (rs.next()) {
                                            // Frequency is a count, not a probability — render as integer
                                            // (REAL → toLong) so the CSV stays human-legible. NULL → empty cell.
                                            val freqRaw = rs.getObject("frequency") as Number?
                                            val freqStr = freqRaw?.toLong()?.toString() ?: ""
                                            printer.printRecord(
                                                rs.getString("word"),
                                                rs.getString("language"),
                                                rs.getInt("length"),
                                                freqStr,
                                                rs.getObject("difficulty")?.toString() ?: "",
                                                rs.getString("clue") ?: "",
                                                rs.getString("source"),
                                                rs.getString("source_license"),
                                            )
                                            rows++
                                        }
                                        rows
                                    }
                                }
                            }
                        }
                    }
                }
            log.info("export_words_complete language={} rows_written={} output={}", language, rowCount, outputPath)
        } finally {
            MDC.remove("correlation_id")
        }
    }

    companion object {
        private val HEADER =
            arrayOf("word", "language", "length", "frequency", "difficulty", "clue", "source", "source_license")

        // ORDER BY language, word for stable git diffs (ADR-0013 §7).
        // LEFT JOIN propagates a lemma's clue to every inflected form
        // (generate-clues only targets lemmas by default — see ADR-0013 §5 amendment).
        private val SELECT_CLUED_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue) AS clue,
                   w.source, w.source_license
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ? AND COALESCE(w.clue, l.clue) IS NOT NULL
            ORDER BY w.language, w.word
            """.trimIndent()

        // --include-clueless variant: every row of the language, regardless of clue.
        private val SELECT_ALL_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue) AS clue,
                   w.source, w.source_license
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ?
            ORDER BY w.language, w.word
            """.trimIndent()

        // --placeholder-clue-from-word variant: when no real clue exists, fall back to the
        // word itself so every row carries a non-blank clue (unblocks grid-api loading).
        private val SELECT_ALL_PLACEHOLDER_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue, w.word) AS clue,
                   w.source, w.source_license
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ?
            ORDER BY w.language, w.word
            """.trimIndent()

        // \n line terminator (Unix; matches git/JVM convention) avoids mixed
        // line endings when the CSV is hand-edited on macOS/Linux. RFC 4180
        // permits CRLF, but the in-repo dataset standardises on LF.
        private fun csvFormat(): CSVFormat =
            CSVFormat.RFC4180
                .builder()
                .setHeader(*HEADER)
                .setRecordSeparator("\n")
                .build()

        private fun defaultOutputPath(language: String): Path = Path.of("grid/api/src/main/resources/words/words-$language.csv")
    }
}
