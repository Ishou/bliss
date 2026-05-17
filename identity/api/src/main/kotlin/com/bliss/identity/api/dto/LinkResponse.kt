package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LinkResponse(
    val authorizeUrl: String,
)
