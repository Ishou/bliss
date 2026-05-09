package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure-domain [ClueCooldownPolicy] interface and its two
 * factory shapes. The policy is the read seam between the application
 * layer (which loads the snapshot from Postgres) and the filler (which
 * stays I/O-free) — see ADR-0031.
 */
class ClueCooldownPolicyTest {
    @Test
    fun `Inert policy reports nothing on cooldown`() {
        val policy = ClueCooldownPolicy.Inert
        assertThat(policy.isOnCooldown(ClueId("EST", "Verbe etre"))).isFalse()
        assertThat(policy.isOnCooldown(ClueId("FE", "Symbole du fer"))).isFalse()
    }

    @Test
    fun `set-backed policy reports membership exactly`() {
        val cooled = ClueId("EST", "Direction cardinale")
        val fresh = ClueId("EST", "Verbe etre")
        val policy = ClueCooldownPolicy.fromSet(setOf(cooled))
        assertThat(policy.isOnCooldown(cooled)).isTrue()
        assertThat(policy.isOnCooldown(fresh)).isFalse()
    }

    @Test
    fun `set-backed policy with empty set behaves like Inert`() {
        val policy = ClueCooldownPolicy.fromSet(emptySet())
        assertThat(policy.isOnCooldown(ClueId("EST", "Verbe etre"))).isFalse()
    }

    @Test
    fun `ClueId equality keys on both word and clue text`() {
        val a = ClueId("EST", "Verbe etre")
        val b = ClueId("EST", "Verbe etre")
        val differentClue = ClueId("EST", "Direction cardinale")
        val differentWord = ClueId("OUI", "Verbe etre")
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(differentClue)
        assertThat(a).isNotEqualTo(differentWord)
    }
}
