// `bliss-worker import-grammalecte` — load Grammalecte's `lexique-grammalecte-fr-v7.7.txt`
// into the words table, populating word + lemma + frequency in a single pass.
// Replaces the unmunch-based `import-words` flow for languages that have a Grammalecte lexicon.
package com.bliss.grid.worker.importer

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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

class ImportGrammalecteCommand : CliktCommand(name = "import-grammalecte") {
    private val log = LoggerFactory.getLogger(ImportGrammalecteCommand::class.java)

    private val input by option("--input", help = "Tab-separated lexique-grammalecte-fr-vX.txt")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val source by option("--source").default("grammalecte")
    private val sourceLicense by option("--source-license").default("MPL-2.0")
    private val batchSize by option("--batch-size").int().default(DEFAULT_BATCH_SIZE)
    private val truncate by option("--truncate", help = "DELETE existing rows for the language before import").flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for import-grammalecte")
        executeImport(ds, input)
    }

    internal fun executeImport(
        ds: DataSource,
        path: Path,
    ) {
        MDC.put("correlation_id", UUID.randomUUID().toString())
        try {
            require(batchSize >= 1) { "--batch-size must be >= 1, got $batchSize" }
            log.info(
                "import_grammalecte_start input={} language={} source={} truncate={} batch_size={}",
                path,
                language,
                source,
                truncate,
                batchSize,
            )

            val rows = parse(path)
            log.info("import_grammalecte_parsed unique_forms={}", rows.size)

            if (truncate) {
                val deleted = truncateLanguage(ds)
                log.info("import_grammalecte_truncated rows_deleted={}", deleted)
            }

            val inserted = insert(ds, rows)
            log.info("import_grammalecte_inserted rows_inserted={}", inserted)

            val recomputed = recomputeDifficulty(ds)
            log.info("import_grammalecte_difficulty_recomputed rows_updated={}", recomputed)

            log.info(
                "import_grammalecte_complete unique_forms={} inserted={} difficulty_recomputed={}",
                rows.size,
                inserted,
                recomputed,
            )
        } finally {
            MDC.remove("correlation_id")
        }
    }

    /**
     * Parse the lexique. Skips comments, the corpus header, and the column header. For each surface
     * form (`Flexion`), keeps the row with the highest `Total occurrences` — when two POS analyses
     * exist for the same form (e.g. "la" as nom vs det), the dominant one wins, which is also the
     * one a crossword solver expects to recognise.
     */
    internal fun parse(path: Path): Map<String, Row> {
        val best = HashMap<String, Row>(600_000)
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            var header: List<String>? = null
            var fIdx = -1
            var lIdx = -1
            var occIdx = -1
            for (raw in reader.lines()) {
                if (raw.isEmpty() || raw.startsWith("#")) continue
                val cols = raw.split('\t')
                if (header == null) {
                    if (cols.firstOrNull() == "id" && "Flexion" in cols) {
                        header = cols
                        fIdx = cols.indexOf("Flexion")
                        lIdx = cols.indexOf("Lemme")
                        occIdx = cols.indexOf("Total occurrences")
                        require(fIdx >= 0 && lIdx >= 0 && occIdx >= 0) {
                            "lexique header missing required columns: $cols"
                        }
                    }
                    continue
                }
                if (cols.size <= occIdx) continue
                val flexion = cols[fIdx]
                if (!isAcceptable(flexion)) continue
                val word = flexion.lowercase()
                val lemma = cols[lIdx].lowercase()
                val occurrences = cols[occIdx].toLongOrNull() ?: 0L
                val candidate = Row(lemma, occurrences)
                val existing = best[word]
                if (existing == null || candidate.occurrences > existing.occurrences) {
                    best[word] = candidate
                }
            }
        }
        return best
    }

    private fun truncateLanguage(ds: DataSource): Int =
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM words WHERE language = ?").use { stmt ->
                stmt.setString(1, language)
                stmt.executeUpdate()
            }
        }

    private fun insert(
        ds: DataSource,
        rows: Map<String, Row>,
    ): Int {
        var inserted = 0
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                rows.entries.forEachIndexed { idx, (word, row) ->
                    stmt.setString(1, word)
                    stmt.setString(2, language)
                    stmt.setString(3, row.lemma)
                    stmt.setFloat(4, row.occurrences.toFloat())
                    stmt.setString(5, source)
                    stmt.setString(6, sourceLicense)
                    stmt.addBatch()
                    if ((idx + 1) % batchSize == 0) {
                        for (n in stmt.executeBatch()) inserted += n.coerceAtLeast(0)
                        conn.commit()
                    }
                }
                for (n in stmt.executeBatch()) inserted += n.coerceAtLeast(0)
                conn.commit()
            }
        }
        return inserted
    }

    /** Same SQL as `ImportFrequenciesCommand`: §4 sigmoid keyed by frequency rank. */
    private fun recomputeDifficulty(ds: DataSource): Int =
        ds.connection.use { conn ->
            conn.autoCommit = false
            val n =
                conn.prepareStatement(RECOMPUTE_SQL).use { stmt ->
                    stmt.setString(1, language)
                    stmt.executeUpdate()
                }
            conn.commit()
            n
        }

    internal data class Row(
        val lemma: String,
        val occurrences: Long,
    )

    companion object {
        private const val DEFAULT_BATCH_SIZE: Int = 2000

        private val INSERT_SQL =
            """
            INSERT INTO words (word, language, lemma, frequency, source, source_license)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (word, language) DO NOTHING
            """.trimIndent()

        private val RECOMPUTE_SQL =
            """
            UPDATE words AS w
            SET difficulty = (
                1.0 / (1.0 + exp(-(0.15 * ln(r.rank::float) + 0.20 * (w.length - 5))))
            )::real
            FROM (
                SELECT word_id,
                       row_number() OVER (ORDER BY frequency DESC NULLS LAST, word ASC) AS rank
                FROM words
                WHERE language = ?
            ) AS r
            WHERE w.word_id = r.word_id
            """.trimIndent()
    }
}
