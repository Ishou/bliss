package com.bliss.identity.infrastructure.oidc

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class AppleClientAssertionSignerTest {
    private val key = ECKeyGenerator(Curve.P_256).keyID("test-key-id").generate()
    private val privatePem: String =
        run {
            val privateBytes = key.toECPrivateKey().encoded
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateBytes)
            "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
        }

    private val signer =
        AppleClientAssertionSigner(
            teamId = "TEAM123",
            keyId = "test-key-id",
            privateKeyPem = privatePem,
            clientId = "com.example.app",
            tokenUrl = "https://appleid.apple.com/auth/token",
        )

    @Test
    fun `assertion is a valid ES256-signed JWT`() {
        val now = Instant.now()
        val raw = signer.sign(now)
        val jwt = SignedJWT.parse(raw)
        val verifier = ECDSAVerifier(key.toPublicJWK())
        assertThat(jwt.verify(verifier)).isEqualTo(true)
    }

    @Test
    fun `header carries the correct alg and kid`() {
        val jwt = SignedJWT.parse(signer.sign(Instant.now()))
        assertThat(jwt.header.algorithm.name).isEqualTo("ES256")
        assertThat(jwt.header.keyID).isEqualTo("test-key-id")
    }

    @Test
    fun `claims carry iss aud sub iat and exp`() {
        val now = Instant.now()
        val jwt = SignedJWT.parse(signer.sign(now))
        val claims = jwt.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo("TEAM123")
        assertThat(claims.audience).isNotNull()
        assertThat(claims.audience.first()).isEqualTo("https://appleid.apple.com/auth/token")
        assertThat(claims.subject).isEqualTo("com.example.app")
        // iat should be close to now (within 5 seconds)
        val iat = claims.issueTime.toInstant().epochSecond
        assertThat(iat).isGreaterThanOrEqualTo(now.epochSecond - 5)
        assertThat(iat).isLessThanOrEqualTo(now.epochSecond + 5)
        // exp is 180 days out
        val exp = claims.expirationTime.toInstant().epochSecond
        assertThat(exp).isEqualTo(iat + 180 * 24 * 3600)
    }
}
