package com.bliss.survey.domain.routing

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test

class KCoveragePolicyTest {
    private val policy =
        KCoveragePolicy(
            targetK =
                mapOf(
                    Tier.HIGH to 3,
                    Tier.MID to 3,
                    Tier.LOW to 3,
                    Tier.EXCLUDED to 2,
                ),
        )

    @Test
    fun `item below target K is not retired`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 2)).isFalse()
    }

    @Test
    fun `item at target K is retired`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 3)).isTrue()
    }

    @Test
    fun `item past target K stays retired (idempotence)`() {
        assertThat(policy.shouldRetire(Tier.MID, ratingCount = 4)).isTrue()
    }

    @Test
    fun `excluded tier uses its own threshold`() {
        assertThat(policy.shouldRetire(Tier.EXCLUDED, ratingCount = 2)).isTrue()
        assertThat(policy.shouldRetire(Tier.EXCLUDED, ratingCount = 1)).isFalse()
    }
}
