package com.bliss.survey.domain.routing

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.bliss.survey.domain.model.Tier
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.next
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StratifiedSamplerTest {
    @Test
    fun `defaults — distribution converges to weights over 10k draws (epsilon 0_02)`() {
        val weights = TierWeights.DEFAULT
        val sampler = StratifiedSampler(weights)
        val rng = Random(42L) // deterministic seed

        val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
        val n = 10_000
        repeat(n) {
            val tier = sampler.pickTier(rng)
            counts[tier] = counts.getValue(tier) + 1
        }

        for ((tier, expected) in weights.weights) {
            val observed = counts.getValue(tier).toDouble() / n
            assertThat(observed, "tier=$tier observed=$observed expected=$expected")
                .isCloseTo(expected, 0.02)
        }
    }

    @Test
    fun `randomised weights — distribution converges`() {
        val weightsArb =
            Arb.bind(
                Arb.double(0.1, 1.0),
                Arb.double(0.1, 1.0),
                Arb.double(0.1, 1.0),
                Arb.double(0.1, 1.0),
            ) { h, m, l, e ->
                val sum = h + m + l + e
                TierWeights(
                    mapOf(
                        Tier.HIGH to h / sum,
                        Tier.MID to m / sum,
                        Tier.LOW to l / sum,
                        Tier.EXCLUDED to e / sum,
                    ),
                )
            }
        val rng = Random(123L)
        repeat(10) {
            val w =
                weightsArb.next(
                    io.kotest.property.RandomSource
                        .seeded(it.toLong()),
                )
            val sampler = StratifiedSampler(w)
            val counts = mutableMapOf<Tier, Int>().withDefault { 0 }
            val n = 20_000
            repeat(n) {
                val tier = sampler.pickTier(rng)
                counts[tier] = counts.getValue(tier) + 1
            }
            for ((tier, expected) in w.weights) {
                val observed = counts.getValue(tier).toDouble() / n
                assertThat(observed, "weights=$w tier=$tier observed=$observed expected=$expected")
                    .isCloseTo(expected, 0.025)
            }
        }
    }
}
