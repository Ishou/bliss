package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.clue.ClueSelectionCriteria
import com.bliss.grid.domain.clue.WordCorpusClueWriter
import com.bliss.grid.domain.clue.WordRow
import java.util.UUID
import javax.sql.DataSource

/** JDBC adapter for [WordCorpusClueWriter]. */
class JdbcWordCorpusClueWriter(
    private val dataSource: DataSource,
) : WordCorpusClueWriter {
    override fun selectRows(criteria: ClueSelectionCriteria): List<WordRow> {
        val conditions =
            buildList {
                add("language = ?")
                if (!criteria.includeAlreadyClued) add("clue IS NULL")
                if (!criteria.includeAllLengths) add("length BETWEEN 2 AND 9")
                if (!criteria.includeAllForms) add("word = lemma")
            }
        // Stable order keeps re-runs predictable when --limit is in play (smoke testing).
        val sql =
            buildString {
                append("SELECT word_id, word, length FROM words WHERE ")
                append(conditions.joinToString(" AND "))
                append(" ORDER BY word_id")
                if (criteria.limit != null) append(" LIMIT ?")
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, criteria.language)
                criteria.limit?.let { stmt.setInt(2, it) }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WordRow(
                                    wordId = rs.getObject("word_id", UUID::class.java),
                                    word = rs.getString("word"),
                                    length = rs.getInt("length"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun writeClue(
        wordId: UUID,
        clue: String,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(UPDATE_SQL).use { stmt ->
                stmt.setString(1, clue)
                stmt.setObject(2, wordId)
                stmt.executeUpdate()
            }
        }
    }

    companion object {
        private const val UPDATE_SQL = "UPDATE words SET clue = ? WHERE word_id = ?"
    }
}
