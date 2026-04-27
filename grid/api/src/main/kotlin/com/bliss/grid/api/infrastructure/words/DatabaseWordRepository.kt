package com.bliss.grid.api.infrastructure.words

import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.text.Normalizer
import javax.sql.DataSource

/**
 * Postgres-backed [WordRepository] for v1 puzzle generation (ADR-0013 §8).
 *
 * Reads from the `words` table populated by the worker module
 * (ADR-0013 §7). Every read is scoped to
 * `language = 'fr' AND length BETWEEN 2 AND 9 AND clue IS NOT NULL`,
 * matching ADR-0013 §2's query window. Notes:
 *
 *   * The `BETWEEN` predicate is intentionally redundant with the
 *     [findByLength] guard — belt + suspenders so a future caller
 *     passing `length = 12` returns empty rather than a row a
 *     downstream component cannot use.
 *   * `clue IS NOT NULL` filters at SQL level: a row without a clue
 *     is unusable for puzzle generation, so excluding it keeps
 *     [Word.definition] non-nullable as the domain expects.
 *   * The DB stores French surface forms with diacritics preserved
 *     (ADR-0013 §2). The domain [Word] invariant is `A-Z` uppercase
 *     only, so this adapter folds via [Normalizer] (NFD + strip
 *     combining marks) and uppercases at the boundary. Rows that
 *     still contain non-`A-Z` characters after folding are dropped.
 */
class DatabaseWordRepository(
    private val dataSource: DataSource,
) : WordRepository {
    private val log = LoggerFactory.getLogger(DatabaseWordRepository::class.java)

    override fun findByLength(length: Int): List<Word> {
        if (length !in MIN_LENGTH..MAX_LENGTH) return emptyList()
        return dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_BY_LENGTH_SQL).use { stmt ->
                stmt.setString(1, LANGUAGE)
                stmt.setInt(2, length)
                stmt.executeQuery().use { rs -> readWords(rs) }
            }
        }
    }

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> =
        findByLength(length).filter { word ->
            pattern.all { (i, ch) -> i in word.text.indices && word.text[i] == ch }
        }

    private fun readWords(rs: ResultSet): List<Word> {
        val out = mutableListOf<Word>()
        var dropped = 0
        while (rs.next()) {
            val raw = rs.getString("word") ?: continue
            val clue = rs.getString("clue") ?: continue
            val folded = foldToAscii(raw)
            if (folded.isEmpty() || !folded.all { it in 'A'..'Z' }) {
                dropped++
                continue
            }
            out.add(Word(text = folded, definition = clue))
        }
        if (dropped > 0) {
            log.debug("database_word_repository_skipped non_ascii_after_fold count={}", dropped)
        }
        return out
    }

    companion object {
        internal const val LANGUAGE: String = "fr"
        internal const val MIN_LENGTH: Int = 2
        internal const val MAX_LENGTH: Int = 9

        // Belt + suspenders: the `length` predicate is the narrow filter,
        // the BETWEEN guards against accidental queries outside the §2
        // window even if a future caller passes a length we don't expect.
        internal val SELECT_BY_LENGTH_SQL: String =
            """
            SELECT word, clue
            FROM words
            WHERE language = ?
              AND length = ?
              AND length BETWEEN $MIN_LENGTH AND $MAX_LENGTH
              AND clue IS NOT NULL
            """.trimIndent()

        /** NFD-decompose, strip combining marks, uppercase. Locale-independent. */
        internal fun foldToAscii(raw: String): String {
            val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            val stripped = decomposed.filter { it.category != CharCategory.NON_SPACING_MARK }
            return stripped.uppercase()
        }
    }
}
