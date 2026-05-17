package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider

sealed class OidcExchangeError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class TokenEndpointRejected(
        val provider: Provider,
        val httpStatus: Int,
        cause: Throwable,
    ) : OidcExchangeError("Token endpoint rejected code for provider=$provider: HTTP $httpStatus", cause)
}
