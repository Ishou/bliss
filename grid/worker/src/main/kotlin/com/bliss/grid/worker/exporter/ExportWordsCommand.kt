// `bliss-worker export-words` — DB → CSV exporter (ADR-0013 §7, §8).
package com.bliss.grid.worker.exporter

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
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
            log.info("export_words_start language={} output={}", language, outputPath)
            outputPath.parent?.let { Files.createDirectories(it) }

            val rowCount =
                ds.connection.use { conn ->
                    conn.prepareStatement(SELECT_SQL).use { stmt ->
                        stmt.setString(1, language)
                        stmt.executeQuery().use { rs ->
                            Files.newOutputStream(outputPath).use { out ->
                                OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                                    CSVPrinter(writer, csvFormat()).use { printer ->
                                        var rows = 0
                                        while (rs.next()) {
                                            printer.printRecord(
                                                rs.getString("word"),
                                                rs.getString("language"),
                                                rs.getInt("length"),
                                                rs.getObject("difficulty")?.toString() ?: "",
                                                rs.getString("clue"),
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
            arrayOf("word", "language", "length", "difficulty", "clue", "source", "source_license")

        // ORDER BY language, word for stable git diffs (ADR-0013 §7).
        private val SELECT_SQL =
            """
            SELECT word, language, length, difficulty, clue, source, source_license
            FROM words
            WHERE language = ? AND clue IS NOT NULL
            ORDER BY language, word
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
