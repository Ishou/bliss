package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.GrammalecteCorpusWriter
import com.bliss.grid.domain.lexicon.GrammalecteEntry
import javax.sql.DataSource

/** JDBC adapter for [GrammalecteCorpusWriter]. */
class JdbcGrammalecteCorpusWriter(
    private val dataSource: DataSource,
    private val batchSize: Int,
) : GrammalecteCorpusWriter {
    init {
        require(batchSize >= 1) { "batchSize must be >= 1, got $batchSize" }
    }

    override fun truncateLanguage(language: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM words WHERE language = ?").use { stmt ->
                stmt.setString(1, language)
                stmt.executeUpdate()
            }
        }

    override fun insertLexique(
        language: String,
        source: String,
        sourceLicense: String,
        entries: Sequence<GrammalecteEntry>,
    ): Int {
        var inserted = 0
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                var rowsInBatch = 0
                for (entry in entries) {
                    stmt.setString(1, entry.word)
                    stmt.setString(2, language)
                    stmt.setString(3, entry.lemma)
                    stmt.setFloat(4, entry.occurrences.toFloat())
                    stmt.setString(5, source)
                    stmt.setString(6, sourceLicense)
                    stmt.addBatch()
                    rowsInBatch++
                    if (rowsInBatch >= batchSize) {
                        for (n in stmt.executeBatch()) inserted += n.coerceAtLeast(0)
                        conn.commit()
                        rowsInBatch = 0
                    }
                }
                for (n in stmt.executeBatch()) inserted += n.coerceAtLeast(0)
                conn.commit()
            }
        }
        return inserted
    }

    companion object {
        private val INSERT_SQL =
            """
            INSERT INTO words (word, language, lemma, frequency, source, source_license)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (word, language) DO NOTHING
            """.trimIndent()
    }
}
