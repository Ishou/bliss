package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeResponse(
    val id: String,
    val displayName: String,
    val providers: List<LinkedProviderDto>,
    val createdAt: String,
)

@Serializable
data class LinkedProviderDto(
    val provider: String,
    val linkedAt: String,
    val emailOptIn: Boolean,
)
