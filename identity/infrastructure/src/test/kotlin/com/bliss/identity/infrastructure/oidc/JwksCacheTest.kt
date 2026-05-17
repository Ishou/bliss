package com.bliss.identity.infrastructure.oidc

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.nimbusds.jose.jwk.JWKSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class JwksCacheTest {
    private val uri1 = "https://example.com/jwks1"
    private val uri2 = "https://example.com/jwks2"

    private fun makeSet(kid: String): JWKSet {
        val key =
            com.nimbusds.jose.jwk.gen
                .RSAKeyGenerator(2048)
                .keyID(kid)
                .generate()
                .toPublicJWK()
        return JWKSet(key)
    }

    @Test
    fun `fetch is called once when cache is cold`() =
        runTest {
            val set1 = makeSet("k1")
            var calls = 0
            val cache =
                JwksCache(ttl = java.time.Duration.ofMinutes(5)) { _ ->
                    calls++
                    set1
                }
            cache.get(uri1)
            cache.get(uri1)
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `fetch is called again after TTL expires`() =
        runTest {
            var calls = 0
            val cache =
                JwksCache(ttl = java.time.Duration.ofSeconds(1), clock = { Instant.ofEpochSecond(calls.toLong()) }) { _ ->
                    calls++
                    makeSet("k$calls")
                }
            cache.get(uri1)
            // simulate time advancing past TTL by incrementing calls-based clock
            // Second call happens at epoch-second=1, which is exactly TTL=1s past epoch=0 -> expired
            cache.get(uri1)
            assertThat(calls).isEqualTo(2)
        }

    @Test
    fun `separate URIs are cached independently`() =
        runTest {
            val set1 = makeSet("k1")
            val set2 = makeSet("k2")
            val cache =
                JwksCache(ttl = java.time.Duration.ofMinutes(5)) { uri ->
                    if (uri == uri1) set1 else set2
                }
            val got1 = cache.get(uri1)
            val got2 = cache.get(uri2)
            assertThat(got1).isNotEqualTo(got2)
            assertThat(got1).isEqualTo(set1)
            assertThat(got2).isEqualTo(set2)
        }
}
