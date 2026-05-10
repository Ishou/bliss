package com.bliss.grid.domain.generation

/** Cooldown key for a (word, clue) pair; keyed on text (not a fabricated id) because Word.clues indices are fragile across CSV regenerations (ADR-0031). */
data class ClueId(
    val wordText: String,
    val clueText: String,
)

/** Pure-domain read seam for clue cooldown; see ADR-0031. [Inert] disables all filtering. */
fun interface ClueCooldownPolicy {
    fun isOnCooldown(id: ClueId): Boolean

    companion object {
        val Inert: ClueCooldownPolicy = ClueCooldownPolicy { false }

        fun fromSet(onCooldown: Set<ClueId>): ClueCooldownPolicy =
            if (onCooldown.isEmpty()) Inert else ClueCooldownPolicy { it in onCooldown }
    }
}
