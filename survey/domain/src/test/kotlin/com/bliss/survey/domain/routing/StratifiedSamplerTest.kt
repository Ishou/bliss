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
    fun `restrictTo limits picks to that subset and renormalises`() {
        val sampler = StratifiedSampler(TierWeights.DEFAULT)
        val rng = Random(123L)
        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 5_000
        val restrict = setOf(Tier.MID)
        repeat(n) {
            val tier = sampler.pickTier(rng, restrictTo = restrict)
            counts[tier] = counts.getValue(tier) + 1
        }
        assertThat(counts.getValue(Tier.MID).toDouble() / n).isCloseTo(1.0, 0.0001)
    }

    @Test
    fun `restrictTo renormalises between two populated tiers`() {
        val sampler = StratifiedSampler(TierWeights.DEFAULT)
        val rng = Random(99L)
        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 20_000
        val restrict = setOf(Tier.HIGH, Tier.MID)
        repeat(n) {
            val tier = sampler.pickTier(rng, restrictTo = restrict)
            counts[tier] = counts.getValue(tier) + 1
        }
        val highShare = counts.getValue(Tier.HIGH).toDouble() / n
        val midShare = counts.getValue(Tier.MID).toDouble() / n
        assertThat(highShare).isCloseTo(0.20 / 0.75, 0.02)
        assertThat(midShare).isCloseTo(0.55 / 0.75, 0.02)
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
