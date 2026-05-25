package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

// RFC 7807 problem-detail object (ADR-0003 §6).
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)

// 422 variant carrying the failed filter-pipeline diagnostic (ADR-0056).
@Serializable
data class CorrectifRejection(
    val type: String,
    val title: String,
    val status: Int,
    val filterId: Int,
    val reason: String,
)
