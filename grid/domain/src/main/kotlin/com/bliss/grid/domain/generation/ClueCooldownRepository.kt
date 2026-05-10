package com.bliss.grid.domain.generation

import java.util.UUID

/** Cooldown scope port — see ADR-0031 for bucket kinds, counter, and TTL semantics. */
interface ClueCooldownRepository {
    /** Returns the active cooldown set for [bucketId]; empty for unknown buckets. */
    fun snapshot(bucketId: UUID): Snapshot

    /** Bumps the bucket counter and upserts one cooldown row per [usedClues]. Must be atomic per bucket. */
    fun recordGeneration(
        bucketId: UUID,
        usedClues: Collection<ClueId>,
        rollMaxInclusive: Int,
    ): Long

    /** GDPR erasure (ADR-0025 §5): removes all rows and the counter for [bucketId]. */
    fun deleteBySession(bucketId: UUID): Int

    data class Snapshot(
        val currentSeq: Long,
        val onCooldown: Set<ClueId>,
    )

    companion object {
        /** Reserved sentinel for the shared daily bucket; the route layer rejects this as X-Session-Id (ADR-0031 §Tampering). */
        val DAILY_SCOPE_ID: UUID = UUID.fromString("00000000-0000-7000-8000-000000000000")
    }
}
