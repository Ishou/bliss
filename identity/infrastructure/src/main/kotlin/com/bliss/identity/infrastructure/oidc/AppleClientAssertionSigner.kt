package com.bliss.identity.infrastructure.oidc

import com.bliss.identity.application.ports.ClientAssertionSigner
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Signs an ES256 client-assertion JWT for Apple's `private_key_jwt` token-endpoint auth. */
class AppleClientAssertionSigner(
    private val teamId: String,
    private val keyId: String,
    private val privateKeyPem: String,
    private val clientId: String,
    private val tokenUrl: String,
) : ClientAssertionSigner {
    private val privateKey: ECPrivateKey by lazy { parsePem(privateKeyPem) }

    override fun sign(now: Instant): String {
        val iat = now.epochSecond
        val exp = iat + 180L * 24 * 3600
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(teamId)
                .subject(clientId)
                .audience(tokenUrl)
                .issueTime(Date(iat * 1000))
                .expirationTime(Date(exp * 1000))
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(privateKey))
        return jwt.serialize()
    }

    private fun parsePem(pem: String): ECPrivateKey {
        val stripped =
            pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(stripped)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
    }
}
