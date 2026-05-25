package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier

data class TierWeights(
    val weights: Map<Tier, Double>,
) {
    init {
        require(weights.keys == Tier.values().toSet()) {
            "weights must cover all 4 tiers (missing: ${Tier.values().toSet() - weights.keys})"
        }
        require(weights.values.all { it >= 0.0 }) { "weights must be non-negative" }
        val sum = weights.values.sum()
        require(sum > 0.0) { "weights must have positive sum" }
    }

    companion object {
        val DEFAULT =
            TierWeights(
                mapOf(
                    Tier.HIGH to 0.20,
                    Tier.MID to 0.55,
                    Tier.LOW to 0.15,
                    Tier.EXCLUDED to 0.10,
                ),
            )
    }
}
