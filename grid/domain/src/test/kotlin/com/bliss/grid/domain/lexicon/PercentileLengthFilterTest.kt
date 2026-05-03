package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsNone
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class PercentileLengthFilterTest {
    private val curatedSource = "bliss"

    private fun row(
        word: String,
        frequency: Long?,
        source: String = "grammalecte",
    ) = ExportRow(
        word = word,
        language = "fr",
        length = word.length,
        frequency = frequency,
        difficulty = null,
        clue = word,
        source = source,
        sourceLicense = "MPL-2.0",
        lemma = word,
    )

    @Test
    fun `keeps top half by frequency within each length bucket`() {
        val rows =
            listOf(
                // length 4: keep top 2 of 4 at ratio 0.5
                row("aaaa", 100),
                row("bbbb", 200),
                row("cccc", 300),
                row("dddd", 400),
                // length 5: keep top 1 of 2 at ratio 0.5
                row("aaaaa", 10),
                row("bbbbb", 20),
            )
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = emptyMap(), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource).map { it.word }

        assertThat(out).containsExactlyInAnyOrder("cccc", "dddd", "bbbbb")
    }

    @Test
    fun `length-specific ratio overrides default`() {
        val rows =
            (1..10).map { row(word = "x".repeat(2), frequency = it.toLong()) } +
                (1..10).map { row(word = "y".repeat(3), frequency = it.toLong()) } +
                (1..10).map { row(word = "z".repeat(4), frequency = it.toLong()) }
        val cfg =
            PercentileLengthFilterConfig(
                keepRatioByLength = mapOf(2 to 0.0, 3 to 0.4),
                defaultKeepRatio = 0.5,
            )

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource)
        val byLen = out.groupBy { it.length }.mapValues { it.value.size }

        // length 2: ratio 0.0 → drop everything
        assertThat(byLen[2] ?: 0).isEqualTo(0)
        // length 3: ratio 0.4 → drop 60% (6) of 10, keep 4
        assertThat(byLen[3]).isEqualTo(4)
        // length 4: default 0.5 → drop 5, keep 5
        assertThat(byLen[4]).isEqualTo(5)
    }

    @Test
    fun `curated rows always survive regardless of frequency`() {
        val rows =
            listOf(
                row("ne", null, source = curatedSource),
                row("fe", null, source = curatedSource),
                row("ck", 99999999L), // grammalecte length-2 — should drop at ratio 0.0
                row("cp", 12345L),
            )
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = mapOf(2 to 0.0), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource).map { it.word }

        assertThat(out).containsExactlyInAnyOrder("ne", "fe")
    }

    @Test
    fun `curated row deduplicates a grammalecte row with the same word and language`() {
        val rows =
            listOf(
                row("ne", 1_000_000L, source = "grammalecte"),
                row("ne", null, source = curatedSource),
            )
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = mapOf(2 to 1.0), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource)

        assertThat(out.size).isEqualTo(1)
        assertThat(out[0].source).isEqualTo(curatedSource)
    }

    @Test
    fun `null frequencies sort lowest and drop first`() {
        val rows =
            listOf(
                row("aaaa", null),
                row("bbbb", 100),
                row("cccc", 200),
                row("dddd", null),
            )
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = emptyMap(), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource).map { it.word }

        // Drop bottom 2 → both nulls drop, top 2 by freq survive.
        assertThat(out).containsExactlyInAnyOrder("bbbb", "cccc")
    }

    @Test
    fun `ratio of 1_0 keeps everything`() {
        val rows = (1..5).map { row("ab", it.toLong()) }
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = mapOf(2 to 1.0), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource)

        assertThat(out.size).isEqualTo(5)
    }

    @Test
    fun `ratio of 0_0 drops everything in that bucket`() {
        val rows = (1..5).map { row("ab", it.toLong()) }
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = mapOf(2 to 0.0), defaultKeepRatio = 1.0)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource)

        assertThat(out).isEmpty()
    }

    @Test
    fun `singleton bucket at ratio 0_5 keeps the single entry`() {
        // size=1, ratio=0.5: drop = floor(1 * 0.5) = 0, keep = 1.
        val rows = listOf(row("solo", 42))
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = emptyMap(), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(rows, cfg, curatedSource)

        assertThat(out.size).isEqualTo(1)
    }

    @Test
    fun `empty input returns empty`() {
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = emptyMap(), defaultKeepRatio = 0.5)
        assertThat(PercentileLengthFilter.apply(emptyList(), cfg, curatedSource)).isEmpty()
    }

    @Test
    fun `regression - known noise drops, known staples survive at length-2 curated-only`() {
        val noise = listOf("ck", "cp", "dh", "mv", "px", "fm", "kt", "qi").map { row(it, 100_000L) }
        val staples = listOf("et", "ou", "le", "la", "du").map { row(it, null, source = curatedSource) }
        val cfg = PercentileLengthFilterConfig(keepRatioByLength = mapOf(2 to 0.0), defaultKeepRatio = 0.5)

        val out = PercentileLengthFilter.apply(noise + staples, cfg, curatedSource).map { it.word }

        assertThat(out).containsExactlyInAnyOrder("et", "ou", "le", "la", "du")
        assertThat(out).containsNone("ck", "cp", "dh", "mv", "px", "fm", "kt", "qi")
    }
}
