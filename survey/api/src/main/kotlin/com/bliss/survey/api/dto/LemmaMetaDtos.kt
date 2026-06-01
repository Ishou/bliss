package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LemmaMetaResponse(
    val priorSenses: List<String>,
    val priorSubTags: List<String>,
)

@Serializable
data class SubTagsRequest(
    val subTags: List<String>,
)
