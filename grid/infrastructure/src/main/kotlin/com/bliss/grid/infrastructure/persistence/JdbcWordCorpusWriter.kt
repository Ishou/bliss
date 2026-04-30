package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.InsertReport
import com.bliss.grid.domain.lexicon.SurfaceFormRecord
import com.bliss.grid.domain.lexicon.WordCorpusWriter
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * JDBC adapter for [WordCorpusWriter]. Writes to the `words` table with
 * `ON CONFLICT (word, language) DO NOTHING` so re-runs are idempotent.
 *
 * Inserts are batched — `batchSize` rows per Hikari round-trip — and committed
 * per batch so a crash mid-import leaves a clean partial state instead of
 * losing the whole run.
 */
class JdbcWordCorpusWriter(
    private val dataSource: DataSource,
    private val batchSize: Int,
) : WordCorpusWriter {
    private val log = LoggerFactory.getLogger(JdbcWordCorpusWriter::class.java)

    init {
        require(batchSize >= 1) { "batchSize must be >= 1, got $batchSize" }
    }

    override fun insertSurfaceForms(records: Sequence<SurfaceFormRecord>): InsertReport {
        var inserted = 0
        var skipped = 0
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                var rowsInBatch = 0
                var totalRows = 0
                for (record in records) {
                    stmt.setString(1, record.word)
                    stmt.setString(2, record.language)
                    stmt.setFloat(3, record.difficulty)
                    stmt.setString(4, record.source)
                    stmt.setString(5, record.sourceLicense)
                    stmt.addBatch()
                    rowsInBatch++
                    totalRows++
                    if (rowsInBatch >= batchSize) {
                        // ON CONFLICT DO NOTHING returns 0 for conflicts; anything else counts as inserted.
                        for (n in stmt.executeBatch()) if (n == 0) skipped++ else inserted++
                        conn.commit()
                        log.info(
                            "import_words_batch_committed rank={} inserted={} skipped={}",
                            totalRows,
                            inserted,
                            skipped,
                        )
                        rowsInBatch = 0
                    }
                }
                for (n in stmt.executeBatch()) if (n == 0) skipped++ else inserted++
                conn.commit()
            }
        }
        return InsertReport(inserted = inserted, skipped = skipped)
    }

    companion object {
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
