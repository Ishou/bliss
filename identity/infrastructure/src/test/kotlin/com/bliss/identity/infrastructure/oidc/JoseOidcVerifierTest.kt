package com.bliss.identity.infrastructure.oidc

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerificationError
import com.bliss.identity.domain.provider.Provider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

class JoseOidcVerifierTest {
    companion object {
        private const val ISS = "https://accounts.example.com"
        private const val AUD = "example-client"
        private const val KID = "test-key-1"

        private val rsaKey =
            RSAKeyGenerator(2048)
                .keyID(KID)
                .generate()

        private val publicJwkSet = JWKSet(rsaKey.toPublicJWK())

        private val provider =
            OidcProvider(
                provider = Provider.GOOGLE,
                issuer = ISS,
                audience = AUD,
                jwksUri = "https://accounts.example.com/jwks",
            )

        private val signer = RSASSASigner(rsaKey)

        @JvmStatic
        @BeforeAll
        fun generateKey() {
            // key is generated at field init above; no additional setup needed.
        }

        private fun signedJwt(
            iss: String = ISS,
            aud: String = AUD,
            sub: String = "user-sub-42",
            iat: Instant = Instant.now().minusSeconds(10),
            exp: Instant = Instant.now().plusSeconds(3600),
            nonce: String? = null,
            kid: String = KID,
        ): String {
            val builder =
                JWTClaimsSet
                    .Builder()
                    .issuer(iss)
                    .audience(aud)
                    .subject(sub)
                    .issueTime(Date.from(iat))
                    .expirationTime(Date.from(exp))
            if (nonce != null) builder.claim("nonce", nonce)
            val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build()
            val jwt = SignedJWT(header, builder.build())
            jwt.sign(signer)
            return jwt.serialize()
        }

        private fun makeVerifier(): JoseOidcVerifier {
            val cache =
                JwksCache(ttl = Duration.ofMinutes(5)) { _ -> publicJwkSet }
            return JoseOidcVerifier(cache)
        }
    }

    @Test
    fun `happy path returns OidcIdToken with correct claims`() =
        runTest {
            val nonce = UUID.randomUUID().toString()
            val raw = signedJwt(sub = "user-sub-1", nonce = nonce)
            val token = makeVerifier().verify(raw, provider)
            assertThat(token.subject.value).isEqualTo("user-sub-1")
            assertThat(token.issuer).isEqualTo(ISS)
            assertThat(token.audience).isEqualTo(AUD)
            assertThat(token.nonce).isEqualTo(nonce)
        }

    @Test
    fun `nonce is null when not present in token`() =
        runTest {
            val raw = signedJwt()
            val token = makeVerifier().verify(raw, provider)
            assertThat(token.nonce).isNull()
        }

    @Test
    fun `IssuerMismatch thrown when iss does not match provider`() =
        runTest {
            val raw = signedJwt(iss = "https://other.example.com")
            assertFailure { makeVerifier().verify(raw, provider) }
                .isInstanceOf(OidcVerificationError.IssuerMismatch::class)
        }

    @Test
    fun `AudienceMismatch thrown when aud does not match provider`() =
        runTest {
            val raw = signedJwt(aud = "wrong-client")
            assertFailure { makeVerifier().verify(raw, provider) }
                .isInstanceOf(OidcVerificationError.AudienceMismatch::class)
        }

    @Test
    fun `TokenExpired thrown for a past expiry`() =
        runTest {
            val raw = signedJwt(exp = Instant.now().minusSeconds(60))
            assertFailure { makeVerifier().verify(raw, provider) }
                .isInstanceOf(OidcVerificationError.TokenExpired::class)
        }

    @Test
    fun `InvalidSignature thrown when token signed with a different key`() =
        runTest {
            val otherKey = RSAKeyGenerator(2048).keyID(KID).generate()
            val otherSigner = RSASSASigner(otherKey)
            val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(ISS)
                    .audience(AUD)
                    .subject("sub-x")
                    .issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build()
            val jwt = SignedJWT(header, claims)
            jwt.sign(otherSigner)
            assertFailure { makeVerifier().verify(jwt.serialize(), provider) }
                .isInstanceOf(OidcVerificationError.InvalidSignature::class)
        }

    @Test
    fun `Malformed thrown for a garbage string`() =
        runTest {
            assertFailure { makeVerifier().verify("not-a-jwt", provider) }
                .isInstanceOf(OidcVerificationError.Malformed::class)
        }

    @Test
    fun `MissingSubject thrown when sub claim is absent`() =
        runTest {
            val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(ISS)
                    .audience(AUD)
                    .issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build()
            val jwt = SignedJWT(header, claims)
            jwt.sign(signer)
            assertFailure { makeVerifier().verify(jwt.serialize(), provider) }
                .isInstanceOf(OidcVerificationError.MissingSubject::class)
        }

    @Test
    fun `issuedAt and expiresAt are preserved`() =
        runTest {
            val iat = Instant.now().minusSeconds(30)
            val exp = Instant.now().plusSeconds(7200)
            val raw = signedJwt(iat = iat, exp = exp)
            val token = makeVerifier().verify(raw, provider)
            // Nimbus truncates to second precision, so compare with that precision.
            assertThat(token.issuedAt).isEqualTo(iat.truncatedTo(ChronoUnit.SECONDS))
            assertThat(token.expiresAt).isEqualTo(exp.truncatedTo(ChronoUnit.SECONDS))
        }
}
