package com.bliss.grid.domain.generation

import java.util.UUID

/**
 * Persistent cooldown bucket for clue selection across grid generations.
 * Per ADR-0031, two bucket kinds use the same port:
 *  - per-session buckets keyed by `X-Session-Id` (UUID v7), and
 *  - the shared daily bucket keyed by [DAILY_SCOPE_ID] across all
 *    `/v1/puzzles/daily` generations.
 *
 * The port lives in `domain` (per the project's preference for ports in
 * domain) so [com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase]
 * can depend on it without adding an application-layer indirection. The
 * implementation is provided by `:grid:infrastructure`.
 */
interface ClueCooldownRepository {
    /**
     * Read the current state of [bucketId]'s cooldowns. The snapshot is
     * the input to a single generation: pre-loaded by the application
     * layer and wrapped in a [ClueCooldownPolicy] for the filler.
     *
     * For an unknown bucket, returns `currentSeq = 0` and an empty
     * `onCooldown` set — matching the fresh-bucket behavior.
     */
    fun snapshot(bucketId: UUID): Snapshot

    /**
     * Atomically bump [bucketId]'s generation counter by 1, then upsert
     * one cooldown row per used `(word, clue)` pair with
     * `cooldown_until_seq = newSeq + rand(1..rollMaxInclusive)`. Returns
     * the post-bump `generation_seq`.
     *
     * Implementations MUST be atomic: a concurrent call on the same
     * bucket either bumps before or after this one, never the same seq.
     *
     * [usedClues] MAY be empty — the counter still bumps. This lets the
     * application layer record "a generation event happened" even if no
     * clues were placed (a degenerate path that doesn't occur today).
     */
    fun recordGeneration(
        bucketId: UUID,
        usedClues: Collection<ClueId>,
        rollMaxInclusive: Int,
    ): Long

    /**
     * Right-to-erasure (RGPD Article 17, ADR-0025 §5). Removes every
     * cooldown row tied to [bucketId] and forgets its counter. Returns
     * the number of cooldown rows removed (0 if the bucket never
     * recorded a generation). Calling this with [DAILY_SCOPE_ID] is
     * permitted but discouraged — it discards the global daily-variety
     * memory.
     */
    fun deleteBySession(bucketId: UUID): Int

    data class Snapshot(
        val currentSeq: Long,
        val onCooldown: Set<ClueId>,
    )

    companion object {
        /**
         * Reserved sentinel UUID for the shared "daily" bucket. Formatted
         * as a UUID v7 to keep schema-level validation uniform but
         * reserved by convention. The route layer rejects an
         * `X-Session-Id` equal to this value with `400 invalid-session-id`
         * to prevent a client from poisoning the shared bucket
         * (ADR-0031 Threat model § Tampering).
         */
        val DAILY_SCOPE_ID: UUID = UUID.fromString("00000000-0000-7000-8000-000000000000")
    }
}
