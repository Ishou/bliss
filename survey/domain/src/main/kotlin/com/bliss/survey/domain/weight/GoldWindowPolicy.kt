package com.bliss.survey.domain.weight

import java.time.Instant

// Future gold windows (per-campaign, graded multipliers) extend here without touching call sites.
class GoldWindowPolicy(
    private val cutoff: Instant,
    private val goldMultiplier: Double,
) {
    fun weightFor(
        createdAt: Instant,
        isMaintainer: Boolean,
    ): Double = if (isMaintainer && !createdAt.isBefore(cutoff)) goldMultiplier else 1.0
}
