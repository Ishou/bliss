package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.DbnaryRepository
import com.bliss.grid.domain.lexicon.DbnarySense
import com.bliss.grid.domain.lexicon.DbnaryWord
import com.bliss.grid.domain.lexicon.UpsertReport
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC adapter for [DbnaryRepository] against the `dbnary_words/senses/
 * synonyms` tables (V3-V5 Flyway migrations).
 *
 * Idempotency: `upsertAll` is one transaction per call. For each input word
 * we issue an upsert against `dbnary_words` (using the `xmax = 0` trick to
 * distinguish insert from update), then delete-and-reinsert that word's
 * senses + synonyms. The cascade FK on `dbnary_senses` and `dbnary_synonyms`
 * means we don't need to scrub them when the word row gets deleted via
 * [deleteByLanguage].
 */
class JdbcDbnaryRepository(
    private val dataSource: DataSource,
) : DbnaryRepository {
    override fun upsertAll(entries: Sequence<DbnaryWord>): UpsertReport {
        var wordsIns = 0
        var wordsUpd = 0
        var sensesWritten = 0
        var synonymsWritten = 0

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(UPSERT_WORD_SQL).use { upsertWord ->
                    conn.prepareStatement(DELETE_SENSES_SQL).use { delSenses ->
                        conn.prepareStatement(INSERT_SENSE_SQL).use { insSense ->
                            conn.prepareStatement(DELETE_SYNS_SQL).use { delSyns ->
                                conn.prepareStatement(INSERT_SYN_SQL).use { insSyn ->
                                    for (entry in entries) {
                                        val (id, inserted) =
                                            upsertWordRow(upsertWord, entry)
                                        if (inserted) wordsIns++ else wordsUpd++

                                        delSenses.setObject(1, id)
                                        delSenses.executeUpdate()
                                        for (sense in entry.senses) {
                                            insSense.setObject(1, id)
                                            insSense.setInt(2, sense.senseIndex)
                                            insSense.setString(3, sense.definitionText)
                                            insSense.setString(4, sense.register)
                                            insSense.executeUpdate()
                                            sensesWritten++
                                        }

                                        delSyns.setObject(1, id)
                                        delSyns.executeUpdate()
                                        for (syn in entry.synonyms) {
                                            insSyn.setObject(1, id)
                                            insSyn.setString(2, syn)
                                            insSyn.executeUpdate()
                                            synonymsWritten++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

        return UpsertReport(wordsIns, wordsUpd, sensesWritten, synonymsWritten)
    }

    override fun deleteByLanguage(language: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM dbnary_words WHERE language = ?").use { stmt ->
                stmt.setString(1, language)
                stmt.executeUpdate()
            }
        }

    override fun findOne(
        language: String,
        lemma: String,
        pos: String,
    ): DbnaryWord? =
        dataSource.connection.use { conn ->
            val wordId = lookupWordId(conn, language, lemma, pos) ?: return@use null
            val senses = loadSenses(conn, wordId)
            val synonyms = loadSynonyms(conn, wordId)
            DbnaryWord(
                lemma = lemma,
                pos = pos,
                language = language,
                senses = senses,
                synonyms = synonyms,
            )
        }

    override fun countByLanguage(language: String): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM dbnary_words WHERE language = ?").use { stmt ->
                stmt.setString(1, language)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    private fun upsertWordRow(
        stmt: java.sql.PreparedStatement,
        entry: DbnaryWord,
    ): Pair<UUID, Boolean> {
        stmt.setString(1, entry.lemma)
        stmt.setString(2, entry.pos)
        stmt.setString(3, entry.language)
        stmt.executeQuery().use { rs ->
            check(rs.next()) { "upsert returned no row for (${entry.language}, ${entry.lemma}, ${entry.pos})" }
            val id = rs.getObject("id", UUID::class.java)
            val inserted = rs.getBoolean("inserted")
            return id to inserted
        }
    }

    private fun lookupWordId(
        conn: Connection,
        language: String,
        lemma: String,
        pos: String,
    ): UUID? =
        conn
            .prepareStatement(
                "SELECT id FROM dbnary_words WHERE language = ? AND lemma = ? AND pos = ?",
            ).use { stmt ->
                stmt.setString(1, language)
                stmt.setString(2, lemma)
                stmt.setString(3, pos)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }

    private fun loadSenses(
        conn: Connection,
        wordId: UUID,
    ): List<DbnarySense> {
        val out = mutableListOf<DbnarySense>()
        conn
            .prepareStatement(
                "SELECT sense_index, definition_text, register FROM dbnary_senses " +
                    "WHERE dbnary_word_id = ? ORDER BY sense_index",
            ).use { stmt ->
                stmt.setObject(1, wordId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(
                            DbnarySense(
                                senseIndex = rs.getInt("sense_index"),
                                definitionText = rs.getString("definition_text"),
                                register = rs.getString("register"),
                            ),
                        )
                    }
                }
            }
        return out
    }

    private fun loadSynonyms(
        conn: Connection,
        wordId: UUID,
    ): List<String> {
        val out = mutableListOf<String>()
        conn
            .prepareStatement(
                "SELECT synonym_lemma FROM dbnary_synonyms " +
                    "WHERE dbnary_word_id = ? ORDER BY synonym_lemma",
            ).use { stmt ->
                stmt.setObject(1, wordId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) out.add(rs.getString(1))
                }
            }
        return out
    }

    companion object {
        // Postgres trick: `xmax = 0` is true on freshly-inserted rows and
        // false (positive xmax) on rows touched by the DO UPDATE branch, so
        // RETURNING surfaces both the id AND whether this was an insert.
        private val UPSERT_WORD_SQL =
            """
            INSERT INTO dbnary_words (lemma, pos, language)
            VALUES (?, ?, ?)
            ON CONFLICT (language, lemma, pos) DO UPDATE SET language = EXCLUDED.language
            RETURNING id, (xmax = 0) AS inserted
            """.trimIndent()

        private val DELETE_SENSES_SQL = "DELETE FROM dbnary_senses WHERE dbnary_word_id = ?"
        private val INSERT_SENSE_SQL =
            """
            INSERT INTO dbnary_senses (dbnary_word_id, sense_index, definition_text, register)
            VALUES (?, ?, ?, ?)
            """.trimIndent()

        private val DELETE_SYNS_SQL = "DELETE FROM dbnary_synonyms WHERE dbnary_word_id = ?"
        private val INSERT_SYN_SQL =
            """
            INSERT INTO dbnary_synonyms (dbnary_word_id, synonym_lemma)
            VALUES (?, ?)
            """.trimIndent()
    }
}
