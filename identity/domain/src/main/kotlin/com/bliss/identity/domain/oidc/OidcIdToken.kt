package com.bliss.identity.domain.oidc

import com.bliss.identity.domain.provider.Subject
import java.time.Instant

/**
 * The subset of ID-token claims we keep after verification. We deliberately
 * do not retain `email`, `name`, `picture`, or any other PII-bearing claim,
 * even when the IdP returns them (Apple sometimes does despite scope=openid).
 * See ADR-0045.
 */
data class OidcIdToken(
    val subject: Subject,
    val issuer: String,
    val audience: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val nonce: String?,
)
