package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.lexicon.ClueCandidateRepository
import com.bliss.grid.application.lexicon.UpsertCandidatesReport
import com.bliss.grid.domain.lexicon.ClueCandidate
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC adapter for [ClueCandidateRepository] against the `clue_candidates`
 * table (V6 migration).
 *
 * `upsertAll` runs as one transaction per call. The Postgres `xmax = 0` trick
 * on the RETURNING clause distinguishes inserts from updates so we can report
 * both counts without a second query. ON CONFLICT updates only the metadata
 * fields (`confidence`, `model_version`, `generated_at`) — the natural key
 * is intentionally invariant.
 */
class JdbcClueCandidateRepository(
    private val dataSource: DataSource,
) : ClueCandidateRepository {
    override fun upsertAll(candidates: Sequence<ClueCandidate>): UpsertCandidatesReport {
        var inserted = 0
        var updated = 0
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    for (c in candidates) {
                        stmt.setObject(1, c.wordId)
                        stmt.setString(2, c.source)
                        c.senseIndex?.let { stmt.setInt(3, it) } ?: stmt.setNull(3, Types.INTEGER)
                        stmt.setString(4, c.clueText)
                        c.confidence?.let { stmt.setBigDecimal(5, BigDecimal.valueOf(it)) }
                            ?: stmt.setNull(5, Types.NUMERIC)
                        c.modelVersion?.let { stmt.setString(6, it) }
                            ?: stmt.setNull(6, Types.VARCHAR)
                        stmt.executeQuery().use { rs ->
                            check(rs.next()) { "upsert returned no row" }
                            if (rs.getBoolean("inserted")) inserted++ else updated++
                        }
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
        return UpsertCandidatesReport(inserted, updated)
    }

    override fun deleteBySource(
        source: String,
        language: String?,
    ): Int =
        dataSource.connection.use { conn ->
            if (language == null) {
                conn.prepareStatement("DELETE FROM clue_candidates WHERE source = ?").use { stmt ->
                    stmt.setString(1, source)
                    stmt.executeUpdate()
                }
            } else {
                conn
                    .prepareStatement(
                        """
                        DELETE FROM clue_candidates cc
                        USING words w
                        WHERE cc.word_id = w.word_id
                          AND cc.source = ?
                          AND w.language = ?
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, source)
                        stmt.setString(2, language)
                        stmt.executeUpdate()
                    }
            }
        }

    override fun findByWord(wordId: UUID): List<ClueCandidate> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_BY_WORD_SQL).use { stmt ->
                stmt.setObject(1, wordId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<ClueCandidate>()
                    while (rs.next()) out.add(rs.toCandidate())
                    out
                }
            }
        }

    override fun findTopBySourcePriority(
        wordId: UUID,
        sourcePriority: List<String>,
    ): ClueCandidate? {
        if (sourcePriority.isEmpty()) return null
        return dataSource.connection.use { conn ->
            val priorityArray = conn.createArrayOf("text", sourcePriority.toTypedArray())
            conn.prepareStatement(SELECT_TOP_BY_PRIORITY_SQL).use { stmt ->
                stmt.setObject(1, wordId)
                // The query references ?::text[] twice (membership filter +
                // ORDER BY position); JDBC binds positionally so we set both.
                stmt.setArray(2, priorityArray)
                stmt.setArray(3, priorityArray)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toCandidate() else null
                }
            }
        }
    }

    override fun countBySource(source: String): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM clue_candidates WHERE source = ?").use { stmt ->
                stmt.setString(1, source)
                stmt.executeQuery().use { if (it.next()) it.getLong(1) else 0L }
            }
        }

    override fun deriveSynonymClues(language: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(DERIVE_SYNONYM_SQL).use { stmt ->
                stmt.setString(1, language)
                stmt.executeUpdate()
            }
        }

    private fun ResultSet.toCandidate(): ClueCandidate {
        val senseIndex = getInt("sense_index").takeUnless { wasNull() }
        val confidence = getBigDecimal("confidence")?.toDouble()
        return ClueCandidate(
            wordId = getObject("word_id", UUID::class.java),
            source = getString("source"),
            clueText = getString("clue_text"),
            senseIndex = senseIndex,
            confidence = confidence,
            modelVersion = getString("model_version"),
        )
    }

    companion object {
        // ON CONFLICT updates only the metadata; the natural key is invariant.
        // RETURNING (xmax = 0) distinguishes inserts from updates.
        private val UPSERT_SQL =
            """
            INSERT INTO clue_candidates
                (word_id, source, sense_index, clue_text, confidence, model_version)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (word_id, source, sense_index, clue_text) DO UPDATE
            SET confidence = EXCLUDED.confidence,
                model_version = EXCLUDED.model_version,
                generated_at = now()
            RETURNING (xmax = 0) AS inserted
            """.trimIndent()

        private val SELECT_BY_WORD_SQL =
            """
            SELECT word_id, source, sense_index, clue_text, confidence, model_version
            FROM clue_candidates
            WHERE word_id = ?
            ORDER BY source ASC, generated_at DESC
            """.trimIndent()

        // Pick a single row by the priority list. `array_position` returns
        // the 1-based index of `source` within `?` (NULL when absent), so
        // ORDER BY ascending puts the highest-priority match first; rows
        // whose source isn't in the list are filtered out by the WHERE.
        private val SELECT_TOP_BY_PRIORITY_SQL =
            """
            SELECT word_id, source, sense_index, clue_text, confidence, model_version
            FROM clue_candidates
            WHERE word_id = ?
              AND source = ANY(?::text[])
            ORDER BY array_position(?::text[], source) ASC,
                     confidence DESC NULLS LAST,
                     generated_at DESC
            LIMIT 1
            """.trimIndent()

        private val DERIVE_SYNONYM_SQL =
            """
            INSERT INTO clue_candidates (word_id, source, sense_index, clue_text)
            SELECT
                w.word_id,
                'dbnary-synonym',
                NULL,
                upper(left(syn.synonym_lemma, 1)) || substring(syn.synonym_lemma, 2)
            FROM words w
            JOIN dbnary_words dw ON dw.language = w.language AND dw.lemma = w.lemma
            JOIN dbnary_synonyms syn ON syn.dbnary_word_id = dw.id
            WHERE w.language = ?
              AND length(syn.synonym_lemma) BETWEEN 1 AND 80
              AND lower(syn.synonym_lemma) <> lower(w.word)
              AND (w.lemma IS NULL OR lower(syn.synonym_lemma) <> lower(w.lemma))
            ON CONFLICT (word_id, source, sense_index, clue_text) DO NOTHING
            """.trimIndent()
    }
}
