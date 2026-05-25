package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

// Wire DTO for the Item schema — camelCase per ADR-0003; matches openapi.yaml verbatim.
@Serializable
data class ItemDto(
    val itemId: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val forceClaimed: Int,
    val longueur: Int,
    val tier: String,
    val isCalibration: Boolean,
)

@Serializable
data class ProgressResponse(
    val itemsRated: Int,
    val calibrationAgreement: Double? = null,
    val lastRatedAt: String? = null,
)

@Serializable
data class ContributionItem(
    val itemId: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val optedOut: Boolean,
    val kCoverage: Int,
    val createdAt: String,
)

@Serializable
data class PreferencesPatch(
    val deleteProposedOnErasure: Boolean,
)
