package com.bliss.survey.domain.weight

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class GoldWindowPolicyTest {
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")
    private val policy = GoldWindowPolicy(cutoff = cutoff, goldMultiplier = 3.0)

    @Test
    fun `pre-cutoff maintainer item is neutral`() {
        assertThat(policy.weightFor(cutoff.minusSeconds(1), isMaintainer = true)).isEqualTo(1.0)
    }

    @Test
    fun `post-cutoff maintainer item is gold`() {
        assertThat(policy.weightFor(cutoff.plusSeconds(1), isMaintainer = true)).isEqualTo(3.0)
    }

    @Test
    fun `post-cutoff non-maintainer item is neutral`() {
        assertThat(policy.weightFor(cutoff.plusSeconds(1), isMaintainer = false)).isEqualTo(1.0)
    }

    @Test
    fun `item created exactly at the cutoff is gold (inclusive)`() {
        assertThat(policy.weightFor(cutoff, isMaintainer = true)).isEqualTo(3.0)
    }
}
