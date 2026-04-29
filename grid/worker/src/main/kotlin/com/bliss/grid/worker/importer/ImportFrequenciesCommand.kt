// `bliss-worker import-frequencies` — load <word> <count> file into words.frequency
// and recompute words.difficulty from frequency rank (ADR-0013 §4 sigmoid).
package com.bliss.grid.worker.importer

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

class ImportFrequenciesCommand : CliktCommand(name = "import-frequencies") {
    private val log = LoggerFactory.getLogger(ImportFrequenciesCommand::class.java)

    private val input by option("--input", help = "UTF-8 frequency file: '<word> <count>' per line")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val batchSize by option("--batch-size").int().default(DEFAULT_BATCH_SIZE)

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for import-frequencies")
        executeImport(ds, input)
    }

    internal fun executeImport(
        ds: DataSource,
        path: Path,
    ) {
        MDC.put("correlation_id", UUID.randomUUID().toString())
        try {
            require(batchSize >= 1) { "--batch-size must be >= 1, got $batchSize" }
            log.info("import_frequencies_start input={} language={} batch_size={}", path, language, batchSize)

            val pairs = parse(path)
            log.info("import_frequencies_parsed entries={}", pairs.size)

            val updated = applyFrequencies(ds, pairs)
            log.info("import_frequencies_applied rows_updated={}", updated)

            val recomputed = recomputeDifficulty(ds)
            log.info("import_frequencies_difficulty_recomputed rows_updated={}", recomputed)

            log.info("import_frequencies_complete entries={} updated={} difficulty_recomputed={}", pairs.size, updated, recomputed)
        } finally {
            MDC.remove("correlation_id")
        }
    }

    internal fun parse(path: Path): List<Pair<String, Long>> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            reader
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val sep = line.indexOf(' ')
                    if (sep <= 0 || sep == line.lastIndex) return@mapNotNull null
                    val word = line.substring(0, sep)
                    val count = line.substring(sep + 1).trim().toLongOrNull() ?: return@mapNotNull null
                    word.lowercase() to count
                }.toList()
        }

    private fun applyFrequencies(
        ds: DataSource,
        pairs: List<Pair<String, Long>>,
    ): Int {
        var updated = 0
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(UPDATE_SQL).use { stmt ->
                pairs.forEachIndexed { idx, (word, count) ->
                    stmt.setFloat(1, count.toFloat())
                    stmt.setString(2, word)
                    stmt.setString(3, language)
                    stmt.addBatch()
                    if ((idx + 1) % batchSize == 0) {
                        for (n in stmt.executeBatch()) updated += n.coerceAtLeast(0)
                        conn.commit()
                    }
                }
                for (n in stmt.executeBatch()) updated += n.coerceAtLeast(0)
                conn.commit()
            }
        }
        return updated
    }

    /**
     * Recompute difficulty for every row of [language] from the rank ordering of `frequency DESC NULLS LAST`.
     * Words with NULL frequency get the highest rank (= hardest), consistent with ADR-0013 §4.
     */
    private fun recomputeDifficulty(ds: DataSource): Int =
        ds.connection.use { conn ->
            conn.autoCommit = false
            val n =
                conn.prepareStatement(RECOMPUTE_DIFFICULTY_SQL).use { stmt ->
                    stmt.setString(1, language)
                    stmt.executeUpdate()
                }
            conn.commit()
            n
        }

    companion object {
        private const val DEFAULT_BATCH_SIZE: Int = 1000

        private val UPDATE_SQL =
            """
            UPDATE words SET frequency = ? WHERE word = ? AND language = ?
            """.trimIndent()
    }
}
