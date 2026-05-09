package com.bliss.grid.domain.generation

/**
 * Identifier for a `(word, clue)` pair used as a cooldown key. Per ADR-0031,
 * we key on `clue_text` rather than a fabricated id because Word.clues is a
 * list whose indices are fragile across CSV regenerations and the corpus is
 * small enough that text-in-key is fine.
 */
data class ClueId(
    val wordText: String,
    val clueText: String,
)

/**
 * Pure-domain read seam consulted by `SkeletonFiller.pickClue` to bias clue
 * selection away from recently-used clues. The application layer pre-loads
 * a snapshot from Postgres and constructs a policy via [fromSet]; the
 * filler stays I/O-free.
 *
 * The default implementation is [Inert] — every `(word, clue)` pair reports
 * fresh, preserving existing behavior whenever the cooldown feature is not
 * wired in (e.g. anonymous fetches, feature flag off, tests that don't care).
 *
 * See ADR-0031 "Per-session (and per-daily) clue cooldown across grid
 * generations" for the design.
 */
fun interface ClueCooldownPolicy {
    fun isOnCooldown(id: ClueId): Boolean

    companion object {
        val Inert: ClueCooldownPolicy = ClueCooldownPolicy { false }

        fun fromSet(onCooldown: Set<ClueId>): ClueCooldownPolicy =
            if (onCooldown.isEmpty()) Inert else ClueCooldownPolicy { it in onCooldown }
    }
}
