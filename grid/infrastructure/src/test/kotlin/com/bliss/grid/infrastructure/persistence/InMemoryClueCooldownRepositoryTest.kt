package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import org.junit.jupiter.api.Test
import java.util.UUID

/** Unit tests for [InMemoryClueCooldownRepository] cooldown semantics — see ADR-0031. */
class InMemoryClueCooldownRepositoryTest {
    private val sessionId: UUID = UUID.randomUUID()

    @Test
    fun `snapshot of unknown session is empty at seq 0`() {
        val repo = InMemoryClueCooldownRepository()
        val snap = repo.snapshot(UUID.randomUUID())
        assertThat(snap.currentSeq).isEqualTo(0L)
        assertThat(snap.onCooldown).isEmpty()
    }

    @Test
    fun `record bumps counter and writes cooldown rows`() {
        val repo = InMemoryClueCooldownRepository(randomCooldown = { 3 })
        val c1 = ClueId("EST", "Verbe etre")
        val c2 = ClueId("FE", "Symbole du fer")

        val seq = repo.recordGeneration(sessionId, listOf(c1, c2), rollMaxInclusive = 8)
        assertThat(seq).isEqualTo(1L)

        val snap = repo.snapshot(sessionId)
        assertThat(snap.currentSeq).isEqualTo(1L)
        // Both clues rolled N=3, cooldown_until = 1+3 = 4 > 1 → on cooldown.
        assertThat(snap.onCooldown).contains(c1)
        assertThat(snap.onCooldown).contains(c2)
    }

    @Test
    fun `clue stays on cooldown for exactly N rolled generations`() {
        for (rolledN in 1..8) {
            val repo = InMemoryClueCooldownRepository(randomCooldown = { rolledN })
            val sid = UUID.randomUUID()
            val tracked = ClueId("X", "Y-$rolledN")

            // Record at seq=1 → cooldown_until = 1 + rolledN.
            repo.recordGeneration(sid, listOf(tracked), rollMaxInclusive = rolledN)

            // Bump (rolledN - 1) more times with disjoint clues so the tracked
            // row is unaffected. After this, currentSeq = rolledN.
            for (i in 2..rolledN) {
                repo.recordGeneration(sid, listOf(ClueId("dummy-$i", "x")), rollMaxInclusive = rolledN)
            }
            val midSnap = repo.snapshot(sid)
            assertThat(midSnap.currentSeq).isEqualTo(rolledN.toLong())
            // current - lastUsed = rolledN - 1 < rolledN → on cooldown.
            assertThat(midSnap.onCooldown).contains(tracked)

            // One more bump → currentSeq = rolledN + 1, cooldown_until = rolledN + 1
            // → current - lastUsed = rolledN, NOT less than rolledN → fresh.
            repo.recordGeneration(sid, listOf(ClueId("dummy-final", "x")), rollMaxInclusive = rolledN)
            val finalSnap = repo.snapshot(sid)
            assertThat(finalSnap.onCooldown).doesNotContain(tracked)
        }
    }

    @Test
    fun `repeating the same clue refreshes its cooldown row`() {
        val repo = InMemoryClueCooldownRepository(randomCooldown = { 1 })
        val clue = ClueId("EST", "Verbe etre")
        repo.recordGeneration(sessionId, listOf(clue), rollMaxInclusive = 1)
        // After seq=1, cooldown_until = 2 → on cooldown at seq=1.
        repo.recordGeneration(sessionId, listOf(ClueId("dummy", "x")), rollMaxInclusive = 1)
        // After seq=2, the original cooldown_until=2 is now <= current=2 → fresh.
        assertThat(repo.snapshot(sessionId).onCooldown).doesNotContain(clue)

        // Re-record the same clue at seq=3 → cooldown_until = 4 → on cooldown.
        repo.recordGeneration(sessionId, listOf(clue), rollMaxInclusive = 1)
        assertThat(repo.snapshot(sessionId).onCooldown).contains(clue)
    }

    @Test
    fun `delete by session removes counter and rows`() {
        val repo = InMemoryClueCooldownRepository(randomCooldown = { 5 })
        repo.recordGeneration(sessionId, listOf(ClueId("EST", "x"), ClueId("FE", "y")), rollMaxInclusive = 8)

        val deleted = repo.deleteBySession(sessionId)
        assertThat(deleted).isEqualTo(2)

        val snap = repo.snapshot(sessionId)
        // After delete the counter is gone; snapshot reads default 0.
        assertThat(snap.currentSeq).isEqualTo(0L)
        assertThat(snap.onCooldown).isEmpty()
    }

    @Test
    fun `delete by unknown session returns 0`() {
        val repo = InMemoryClueCooldownRepository()
        assertThat(repo.deleteBySession(UUID.randomUUID())).isEqualTo(0)
    }

    @Test
    fun `buckets are isolated per session`() {
        val repo = InMemoryClueCooldownRepository(randomCooldown = { 8 })
        val sessionA = UUID.randomUUID()
        val sessionB = UUID.randomUUID()
        val daily = ClueCooldownRepository.DAILY_SCOPE_ID
        val clue = ClueId("EST", "Verbe etre")

        repo.recordGeneration(sessionA, listOf(clue), rollMaxInclusive = 8)

        assertThat(repo.snapshot(sessionA).onCooldown).contains(clue)
        assertThat(repo.snapshot(sessionB).onCooldown).doesNotContain(clue)
        assertThat(repo.snapshot(daily).onCooldown).doesNotContain(clue)
    }

    @Test
    fun `daily scope id is a stable sentinel UUID`() {
        // Reserved per ADR-0031. Pinning the value in tests catches an
        // accidental constant change that would silently corrupt previously
        // stored daily-bucket rows.
        assertThat(ClueCooldownRepository.DAILY_SCOPE_ID)
            .isEqualTo(UUID.fromString("00000000-0000-7000-8000-000000000000"))
    }
}
