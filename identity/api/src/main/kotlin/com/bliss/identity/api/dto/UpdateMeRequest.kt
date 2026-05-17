package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

// PATCH /v1/users/me request body. Both fields optional per openapi.yaml UserUpdate
// schema; absence = no change. `emailOptIn` is currently a no-op in UpdateMeUseCase
// (storage + OIDC re-prompt design deferred) but accepted on the wire so the client
// shape is stable across future activations.
@Serializable
data class UpdateMeRequest(
    val displayName: String? = null,
    val emailOptIn: Boolean? = null,
)
