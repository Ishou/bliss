package com.bliss.survey.domain.routing

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StratifiedSamplerTest {
    @Test
    fun `distribution converges to weights over 10k draws within 0_02`() {
        val weights = TierWeights.DEFAULT
        val sampler = StratifiedSampler(weights)
        val rng = Random(42L)

        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 10_000
        repeat(n) {
            val tier = sampler.pickTier(rng)
            counts[tier] = counts.getValue(tier) + 1
        }

        for ((tier, expected) in weights.weights) {
            val observed = counts.getValue(tier).toDouble() / n
            assertThat(observed).isCloseTo(expected, 0.02)
        }
    }

    @Test
    fun `uniform weights yield uniform distribution`() {
        val weights =
            TierWeights(
                mapOf(
                    Tier.HIGH to 0.25,
                    Tier.MID to 0.25,
                    Tier.LOW to 0.25,
                    Tier.EXCLUDED to 0.25,
                ),
            )
        val sampler = StratifiedSampler(weights)
        val rng = Random(7L)
        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 20_000
        repeat(n) {
            val tier = sampler.pickTier(rng)
            counts[tier] = counts.getValue(tier) + 1
        }
        for ((tier, expected) in weights.weights) {
            val observed = counts.getValue(tier).toDouble() / n
            assertThat(observed).isCloseTo(expected, 0.02)
        }
    }
}
