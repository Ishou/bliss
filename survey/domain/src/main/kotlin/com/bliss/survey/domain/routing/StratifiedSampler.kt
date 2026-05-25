package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier
import kotlin.random.Random

class StratifiedSampler(
    weights: TierWeights,
) {
    private data class Bucket(
        val tier: Tier,
        val cumulative: Double,
    )

    private val buckets: List<Bucket>
    private val total: Double

    init {
        val sorted = weights.weights.entries.sortedBy { it.key.name }
        var running = 0.0
        buckets =
            sorted.map { (tier, w) ->
                running += w
                Bucket(tier, running)
            }
        total = running
    }

    fun pickTier(rng: Random): Tier {
        val r = rng.nextDouble() * total
        // small N; linear scan beats binary search overhead in practice
        for (b in buckets) if (r < b.cumulative) return b.tier
        return buckets.last().tier
    }
}
