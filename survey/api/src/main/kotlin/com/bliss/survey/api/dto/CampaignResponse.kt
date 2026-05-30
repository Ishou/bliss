package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CampaignResponse(
    val campaignId: String,
    val batchLabel: String,
    val openedAt: String,
    val closedAt: String?,
)
