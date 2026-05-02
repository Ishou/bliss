package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.ExportRow
import com.bliss.grid.domain.lexicon.ExportSelectionCriteria
import com.bliss.grid.domain.lexicon.WordCorpusReader
import javax.sql.DataSource

/** JDBC adapter for [WordCorpusReader]. */
class JdbcWordCorpusReader(
    private val dataSource: DataSource,
) : WordCorpusReader {
    override fun <T> withExportRows(
        criteria: ExportSelectionCriteria,
        block: (Sequence<ExportRow>) -> T,
    ): T {
        // --placeholder-clue-from-word implies emitting every row (the word itself is the
        // fallback clue), so it pulls in the all-rows SELECT regardless of --include-clueless.
        val sql =
            when {
                criteria.placeholderClueFromWord -> SELECT_ALL_PLACEHOLDER_SQL
                criteria.includeClueless -> SELECT_ALL_SQL
                else -> SELECT_CLUED_SQL
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, criteria.language)
                stmt.executeQuery().use { rs ->
                    val rows =
                        sequence {
                            while (rs.next()) {
                                // Frequency is a count, not a probability — render as integer.
                                val freqRaw = rs.getObject("frequency") as Number?
                                val difficultyRaw = rs.getObject("difficulty") as Number?
                                val word = rs.getString("word")
                                yield(
                                    ExportRow(
                                        word = word,
                                        language = rs.getString("language"),
                                        length = rs.getInt("length"),
                                        frequency = freqRaw?.toLong(),
                                        difficulty = difficultyRaw?.toFloat(),
                                        clue = rs.getString("clue") ?: "",
                                        source = rs.getString("source"),
                                        sourceLicense = rs.getString("source_license"),
                                        // Lemma is nullable in the schema (lemma forms self-reference;
                                        // legacy rows may be unset). Fall back to the word itself so
                                        // every export row carries a non-null key for dedup.
                                        lemma = rs.getString("lemma") ?: word,
                                    ),
                                )
                            }
                        }
                    block(rows)
                }
            }
        }
    }

    companion object {
        // ORDER BY language, word for stable git diffs (ADR-0013 §7).
        // LEFT JOIN propagates a lemma's clue to every inflected form
        // (generate-clues only targets lemmas by default — see ADR-0013 §5 amendment).
        private val SELECT_CLUED_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue) AS clue,
                   w.source, w.source_license, w.lemma
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ? AND COALESCE(w.clue, l.clue) IS NOT NULL
            ORDER BY w.language, w.word
            """.trimIndent()

        private val SELECT_ALL_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue) AS clue,
                   w.source, w.source_license, w.lemma
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ?
            ORDER BY w.language, w.word
            """.trimIndent()

        private val SELECT_ALL_PLACEHOLDER_SQL =
            """
            SELECT w.word, w.language, w.length, w.frequency, w.difficulty,
                   COALESCE(w.clue, l.clue, w.word) AS clue,
                   w.source, w.source_license, w.lemma
            FROM words w
            LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
            WHERE w.language = ?
            ORDER BY w.language, w.word
            """.trimIndent()
    }
}
