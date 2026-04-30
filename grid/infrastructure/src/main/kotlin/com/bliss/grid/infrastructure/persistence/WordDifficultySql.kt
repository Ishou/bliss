package com.bliss.grid.infrastructure.persistence

// ADR-0013 §4: sigmoid(α·ln(rank) + β·(length − 5)) with α=0.15, β=0.20, pivot=5.
// Single source of truth shared by JdbcWordDifficultyRecomputer and any other adapter
// that needs to restate the formula in SQL.
internal val RECOMPUTE_DIFFICULTY_SQL =
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
