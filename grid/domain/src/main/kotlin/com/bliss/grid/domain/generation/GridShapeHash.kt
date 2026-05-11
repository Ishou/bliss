package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import java.security.MessageDigest

/**
 * Canonical fingerprint over a grid's *shape* — clue positions, directions, and
 * slot lengths. Words and clue text are intentionally excluded; this is a
 * structural metric used by the benchmark to track per-`(width, height)` shape
 * diversity across many generations.
 *
 * The hash is SHA-1 over a pipe-delimited, lexicographically sorted encoding
 * of `(row, col, direction, length)` tuples. SHA-1 is chosen for low collision
 * probability at zero crypto cost; we only need a stable fingerprint, not a
 * security primitive.
 */
object GridShapeHash {
    /** Pre-fill fingerprint from planner output, before any words land. */
    fun of(slots: List<WordSlot>): String {
        val tuples =
            slots
                .map { encode(it.cluePosition.row.value, it.cluePosition.column.value, it.direction.name, it.length) }
                .sorted()
        return sha1Hex(tuples.joinToString("|"))
    }

    /** Post-fill fingerprint from a finished Grid via its placements. */
    fun of(grid: Grid): String {
        val tuples =
            grid.placements
                .map { encode(it.cluePosition.row.value, it.cluePosition.column.value, it.direction.name, it.word.text.length) }
                .sorted()
        return sha1Hex(tuples.joinToString("|"))
    }

    private fun encode(
        row: Int,
        col: Int,
        direction: String,
        length: Int,
    ): String = "$row,$col,$direction,$length"

    private fun sha1Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
