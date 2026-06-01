package com.bliss.survey.application.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GlossNormalizerTest {
    @Test
    fun `strips leading determiner and diacritics`() {
        assertThat(GlossNormalizer.normalize("L'animal félin")).isEqualTo("animal felin")
        assertThat(GlossNormalizer.normalize("la souris")).isEqualTo("souris")
        assertThat(GlossNormalizer.normalize("les chats")).isEqualTo("chats")
    }

    @Test
    fun `accepts curly apostrophe in determiner`() {
        assertThat(GlossNormalizer.normalize("l’animal félin")).isEqualTo("animal felin")
    }

    @Test
    fun `lowercases and collapses whitespace`() {
        assertThat(GlossNormalizer.normalize("  Animal   Félin  ")).isEqualTo("animal felin")
    }

    @Test
    fun `idempotent on already-normalized form`() {
        val once = GlossNormalizer.normalize("L'animal félin")
        val twice = GlossNormalizer.normalize(once)
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `preserves interior determiners`() {
        assertThat(GlossNormalizer.normalize("rive de la mer")).isEqualTo("rive de la mer")
    }

    @Test
    fun `treats equivalent forms as the same key`() {
        val a = GlossNormalizer.normalize("L'animal félin")
        val b = GlossNormalizer.normalize("animal felin")
        val c = GlossNormalizer.normalize("ANIMAL FÉLIN")
        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo(c)
    }
}
