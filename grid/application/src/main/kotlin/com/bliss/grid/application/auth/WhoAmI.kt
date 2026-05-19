package com.bliss.grid.application.auth

import java.util.UUID

/**
 * Identity claims surfaced by [CookieVerifier]. Grid's domain is thin: this layer
 * passes the values through to the hint-usage repository without minting bespoke
 * value classes (mirrors the lightweight shape called out in Phase 6b's plan).
 */
data class WhoAmI(
    val userId: UUID,
    val displayName: String,
)
