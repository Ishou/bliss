package com.bliss.grid.application.auth

import java.util.UUID

data class WhoAmI(
    val userId: UUID,
    val displayName: String,
)
