package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier
import kotlin.random.Random

class StratifiedSampler(
    private val weights: TierWeights,
) {
    private data class Bucket(
        val tier: Tier,
        val cumulative: Double,
    )

    private val fullBuckets: List<Bucket>
    private val fullTotal: Double

    init {
        val sorted = weights.weights.entries.sortedBy { it.key.name }
        var running = 0.0
        fullBuckets =
            sorted.map { (tier, w) ->
                running += w
                Bucket(tier, running)
            }
        fullTotal = running
    }

    // `restrictTo` lets callers exclude tiers known to be empty without burning retry budget;
    // weights are renormalised over the subset for this draw.
    fun pickTier(
        rng: Random,
        restrictTo: Set<Tier>? = null,
    ): Tier {
        if (restrictTo == null) {
            val r = rng.nextDouble() * fullTotal
            for (b in fullBuckets) if (r < b.cumulative) return b.tier
            return fullBuckets.last().tier
        }
        require(restrictTo.isNotEmpty()) { "restrictTo must be non-empty" }
        var subTotal = 0.0
        val subBuckets =
            buildList {
                for ((tier, w) in weights.weights.entries.sortedBy { it.key.name }) {
                    if (tier !in restrictTo || w <= 0.0) continue
                    subTotal += w
                    add(Bucket(tier, subTotal))
                }
            }
        require(subBuckets.isNotEmpty()) { "no positive weight among restricted tiers" }
        val r = rng.nextDouble() * subTotal
        for (b in subBuckets) if (r < b.cumulative) return b.tier
        return subBuckets.last().tier
    }
}
