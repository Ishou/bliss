// `bliss-worker import-words` — Hunspell-fr surface-form ingest (ADR-0013 §2, §4, §7).
package com.bliss.grid.worker.importer

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

class ImportWordsCommand : CliktCommand(name = "import-words") {
    private val log = LoggerFactory.getLogger(ImportWordsCommand::class.java)

    private val input by option("--input", help = "UTF-8 surface-form list (one word per line)")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val source by option("--source").default("hunspell-fr")
    private val sourceLicense by option("--source-license").default("MPL-2.0")
    private val batchSize by option("--batch-size").int().default(DEFAULT_BATCH_SIZE)

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for import-words")
        executeImport(ds, input)
    }

    /** Visible for the testcontainers integration test, which provides its own [DataSource]. */
    internal fun executeImport(
        ds: DataSource,
        path: Path,
    ) {
        require(batchSize >= 1) { "--batch-size must be >= 1, got $batchSize" }
        log.info(
            "import_words_start input={} language={} source={} source_license={} batch_size={}",
            path,
            language,
            source,
            sourceLicense,
            batchSize,
        )
        val lines = Files.newBufferedReader(path).use { it.readLines() }
        val kept = filterAndSort(lines.asSequence())
        log.info("import_words_filter_complete total_read={} kept={}", lines.size, kept.size)

        var inserted = 0
        var skipped = 0
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                kept.forEachIndexed { index, word ->
                    val rank = index + 1
                    stmt.setString(1, word)
                    stmt.setString(2, language)
                    stmt.setFloat(3, difficulty(rank, word.length))
                    stmt.setString(4, source)
                    stmt.setString(5, sourceLicense)
                    stmt.addBatch()
                    if (rank % batchSize == 0) {
                        // ON CONFLICT DO NOTHING returns 0 for conflicts; anything else counts as inserted.
                        for (n in stmt.executeBatch()) if (n == 0) skipped++ else inserted++
                        conn.commit()
                        log.info("import_words_batch_committed rank={} inserted={} skipped={}", rank, inserted, skipped)
                    }
                }
                for (n in stmt.executeBatch()) if (n == 0) skipped++ else inserted++
                conn.commit()
            }
        }
        log.info(
            "import_words_complete total_read={} kept={} inserted={} skipped_on_conflict={}",
            lines.size,
            kept.size,
            inserted,
            skipped,
        )
    }

    companion object {
        private const val DEFAULT_BATCH_SIZE: Int = 1000

        // Omit `frequency` (Hunspell-fr ships no frequency signal — ADR-0013 §4),
        // `length` (generated), `id`/`word_id`/`created_at` (defaulted), `clue` (PR3).
        private val INSERT_SQL =
            """
            INSERT INTO words (word, language, difficulty, source, source_license)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (word, language) DO NOTHING
            """.trimIndent()
    }
}
