package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

// Wire shape for the `Item` schema in survey/api/openapi.yaml — camelCase by
// ADR-0003 convention; do not snake_case (regenerated TS types depend on it).
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
