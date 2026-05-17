package com.bliss.identity.api.auth

import java.net.URI

// Open-redirect guard. Accepts only return_to URLs whose scheme+host(+port) match
// one of the configured allowed origins exactly. Path and query are unrestricted.
class ReturnToValidator(
    allowedOrigins: List<String>,
) {
    private val normalized: Set<String> = allowedOrigins.map(::normalize).toSet()

    fun isAllowed(returnTo: String): Boolean {
        val uri = runCatching { URI.create(returnTo) }.getOrNull() ?: return false
        if (!uri.isAbsolute) return false
        if (uri.userInfo != null) return false
        val scheme = uri.scheme ?: return false
        val host = uri.host ?: return false
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://$host$port".lowercase()
        return origin in normalized
    }

    private fun normalize(origin: String): String = origin.trim().lowercase().removeSuffix("/")
}
