package com.bliss.survey.domain.routing

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isCloseTo
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test

class TierWeightsTest {
    @Test
    fun `defaults sum to one`() {
        val w = TierWeights.DEFAULT
        val sum = w.weights.values.sum()
        assertThat(sum).isCloseTo(1.0, 0.0001)
    }

    @Test
    fun `weights must be non-negative`() {
        assertFailure {
            TierWeights(
                mapOf(
                    Tier.HIGH to -0.1,
                    Tier.MID to 1.1,
                    Tier.LOW to 0.0,
                    Tier.EXCLUDED to 0.0,
                ),
            )
        }
    }

    @Test
    fun `weights must cover all four tiers`() {
        assertFailure { TierWeights(mapOf(Tier.HIGH to 0.5, Tier.MID to 0.5)) }
    }
}
