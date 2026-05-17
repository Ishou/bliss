package com.bliss.identity.infrastructure.oidc

import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerificationError
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.text.ParseException
import java.time.Instant

private fun Provider.toJWSAlgorithm(): JWSAlgorithm =
    when (this) {
        Provider.GOOGLE -> JWSAlgorithm.RS256
        Provider.APPLE -> JWSAlgorithm.ES256
    }

class JoseOidcVerifier(
    private val jwksCache: JwksCache,
    private val clock: () -> Instant = Instant::now,
) : OidcVerifier {
    override suspend fun verify(
        rawIdToken: String,
        provider: OidcProvider,
    ): OidcIdToken {
        val jwkSet =
            try {
                jwksCache.get(provider.jwksUri)
            } catch (e: Exception) {
                throw OidcVerificationError.JwksUnavailable(e)
            }

        val processor = DefaultJWTProcessor<SecurityContext>()
        val keySelector =
            JWSVerificationKeySelector(
                provider.provider.toJWSAlgorithm(),
                ImmutableJWKSet(jwkSet),
            )
        processor.jwsKeySelector = keySelector
        processor.jwtClaimsSetVerifier =
            DefaultJWTClaimsVerifier(
                JWTClaimsSet
                    .Builder()
                    .issuer(provider.issuer)
                    .audience(provider.audience)
                    .build(),
                setOf("sub", "iat", "exp"),
            )

        val unverifiedExp: Instant? =
            try {
                SignedJWT
                    .parse(rawIdToken)
                    .jwtClaimsSet.expirationTime
                    ?.toInstant()
            } catch (_: ParseException) {
                null
            }

        val claims =
            try {
                processor.process(rawIdToken, null)
            } catch (e: ParseException) {
                throw OidcVerificationError.Malformed(e)
            } catch (e: BadJWTException) {
                mapBadJwtException(e, provider, unverifiedExp)
            } catch (e: BadJOSEException) {
                throw OidcVerificationError.InvalidSignature()
            } catch (e: Exception) {
                throw OidcVerificationError.Malformed(e)
            }

        val sub = claims.subject ?: throw OidcVerificationError.MissingSubject()
        if (sub.isBlank()) throw OidcVerificationError.MissingSubject()

        val iat =
            claims.issueTime?.toInstant()
                ?: throw OidcVerificationError.Malformed(IllegalStateException("Missing iat claim after verification"))
        val exp =
            claims.expirationTime?.toInstant()
                ?: throw OidcVerificationError.Malformed(IllegalStateException("Missing exp claim after verification"))

        return OidcIdToken(
            subject = Subject.of(sub),
            issuer = claims.issuer,
            audience = claims.audience.firstOrNull() ?: provider.audience,
            issuedAt = iat,
            expiresAt = exp,
            nonce = claims.getStringClaim("nonce"),
        )
    }

    private fun mapBadJwtException(
        e: BadJWTException,
        provider: OidcProvider,
        exp: Instant?,
    ): Nothing {
        val message = e.message ?: ""
        throw when {
            message.contains("expir", ignoreCase = true) ->
                OidcVerificationError.TokenExpired(exp ?: clock())
            message.contains(" iss ", ignoreCase = true) || message.startsWith("JWT iss ") ->
                OidcVerificationError.IssuerMismatch(
                    expected = provider.issuer,
                    got = message,
                )
            message.contains(" aud ", ignoreCase = true) || message.startsWith("JWT aud ") ->
                OidcVerificationError.AudienceMismatch(
                    expected = provider.audience,
                    got = message,
                )
            message.contains("sub", ignoreCase = true) && message.contains("missing", ignoreCase = true) ->
                OidcVerificationError.MissingSubject()
            else -> OidcVerificationError.Malformed(e)
        }
    }
}
