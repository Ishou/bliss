package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.WordFrequency
import com.bliss.grid.domain.lexicon.WordFrequencyUpdater
import javax.sql.DataSource

/**
 * JDBC adapter for [WordFrequencyUpdater]. Batched `UPDATE words SET frequency = ?`
 * keyed on `(word, language)`. Difficulty recomputation is a separate port — see
 * [JdbcWordDifficultyRecomputer].
 */
class JdbcWordFrequencyUpdater(
    private val dataSource: DataSource,
    private val batchSize: Int,
) : WordFrequencyUpdater {
    init {
        require(batchSize >= 1) { "batchSize must be >= 1, got $batchSize" }
    }

    override fun applyFrequencies(
        language: String,
        frequencies: Sequence<WordFrequency>,
    ): Int {
        var updated = 0
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(UPDATE_SQL).use { stmt ->
                var rowsInBatch = 0
                for (entry in frequencies) {
                    stmt.setFloat(1, entry.count.toFloat())
                    stmt.setString(2, entry.word)
                    stmt.setString(3, language)
                    stmt.addBatch()
                    rowsInBatch++
                    if (rowsInBatch >= batchSize) {
                        for (n in stmt.executeBatch()) updated += n.coerceAtLeast(0)
                        conn.commit()
                        rowsInBatch = 0
                    }
                }
                for (n in stmt.executeBatch()) updated += n.coerceAtLeast(0)
                conn.commit()
            }
        }
        return updated
    }

    companion object {
        private val UPDATE_SQL =
            """
            UPDATE words SET frequency = ? WHERE word = ? AND language = ?
            """.trimIndent()
    }
}
