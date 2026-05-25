package com.bliss.survey.domain.routing

import com.bliss.survey.domain.model.Tier

data class KCoveragePolicy(
    val targetK: Map<Tier, Int>,
) {
    init {
        require(targetK.keys == Tier.entries.toSet()) { "targetK must cover all 4 tiers" }
        require(targetK.values.all { it >= 1 }) { "targetK values must be >= 1" }
    }

    fun shouldRetire(
        tier: Tier,
        ratingCount: Int,
    ): Boolean = ratingCount >= (targetK[tier] ?: error("missing tier $tier"))

    companion object {
        val DEFAULT =
            KCoveragePolicy(
                mapOf(
                    Tier.HIGH to 3,
                    Tier.MID to 3,
                    Tier.LOW to 3,
                    Tier.EXCLUDED to 2,
                ),
            )
    }
}
