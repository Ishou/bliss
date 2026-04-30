package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.WordDifficultyRecomputer
import javax.sql.DataSource

/** JDBC adapter for [WordDifficultyRecomputer]. */
class JdbcWordDifficultyRecomputer(
    private val dataSource: DataSource,
) : WordDifficultyRecomputer {
    override fun recomputeDifficulty(language: String): Int =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            val n =
                conn.prepareStatement(RECOMPUTE_DIFFICULTY_SQL).use { stmt ->
                    stmt.setString(1, language)
                    stmt.executeUpdate()
                }
            conn.commit()
            n
        }
}
