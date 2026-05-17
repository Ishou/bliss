package com.bliss.identity.api.auth

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class ReturnToValidatorTest {
    private val v =
        ReturnToValidator(
            listOf("https://wordsparrow.io", "https://app.wordsparrow.io", "http://localhost:5173"),
        )

    @Test
    fun `exact origin allowed`() {
        assertThat(v.isAllowed("https://wordsparrow.io/play")).isTrue()
        assertThat(v.isAllowed("https://app.wordsparrow.io/account?x=1")).isTrue()
        assertThat(v.isAllowed("http://localhost:5173/")).isTrue()
    }

    @Test
    fun `wrong host rejected`() {
        assertThat(v.isAllowed("https://evil.com/")).isFalse()
        assertThat(v.isAllowed("https://wordsparrow.io.evil.com/")).isFalse()
    }

    @Test
    fun `wrong scheme rejected`() {
        assertThat(v.isAllowed("http://wordsparrow.io/")).isFalse()
    }

    @Test
    fun `wrong port rejected`() {
        assertThat(v.isAllowed("http://localhost:1234/")).isFalse()
    }

    @Test
    fun `userinfo rejected`() {
        assertThat(v.isAllowed("https://attacker@wordsparrow.io/")).isFalse()
    }

    @Test
    fun `relative url rejected`() {
        assertThat(v.isAllowed("/play")).isFalse()
    }

    @Test
    fun `malformed rejected`() {
        assertThat(v.isAllowed("ht!tps://wordsparrow.io")).isFalse()
        assertThat(v.isAllowed("")).isFalse()
    }
}
