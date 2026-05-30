package com.bliss.survey.domain.model

import java.time.Instant

data class Campaign(
    val id: CampaignId,
    val batchLabel: String,
    val openedAt: Instant,
    val closedAt: Instant?,
) {
    init {
        require(batchLabel.isNotBlank()) { "batchLabel must not be blank" }
    }

    val isOpen: Boolean get() = closedAt == null
    val isClosed: Boolean get() = closedAt != null
}
