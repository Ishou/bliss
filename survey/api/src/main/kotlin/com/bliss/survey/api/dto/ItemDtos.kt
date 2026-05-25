package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ItemDto(
    val item_id: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val force_claimed: Int,
    val longueur: Int,
    val tier: String,
    val is_calibration: Boolean,
)

@Serializable
data class ProgressResponse(
    val items_rated: Int,
    val calibration_agreement: Double? = null,
    val last_rated_at: String? = null,
)

@Serializable
data class ContributionItemDto(
    val item_id: String,
    val mot: String,
    val definition: String,
    val pos: String,
    val categorie: String,
    val style: String,
    val opted_out: Boolean,
    val k_coverage: Int,
    val created_at: String,
)

@Serializable
data class PreferencesPatch(
    val delete_proposed_on_erasure: Boolean,
)
