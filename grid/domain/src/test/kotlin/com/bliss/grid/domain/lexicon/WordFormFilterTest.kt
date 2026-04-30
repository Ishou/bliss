package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

@OptIn(ExperimentalKotest::class)
class WordFormFilterTest {
    @Test
    fun `drops non-letter chars, drops capitalized, dedupes, sorts, lowercases`() {
        val raw = sequenceOf("chat", "Paris", "c'est", "covid19", "hello world", "écharpe", "chat", "éCole")
        assertThat(filterAndSort(raw)).containsExactly("chat", "écharpe", "école")
    }

    @Test
    fun `non-letter characters are always rejected`() =
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.string(1..10, Codepoint.az()),
                Arb.element('\'', '-', '0', '5', ' ', '.', '!'),
            ) { letters, junk ->
                assertThat(isAcceptable(letters + junk)).isFalse()
            }
        }

    @Test
    fun `capitalized first letter is always rejected, lowercase letters always accepted`() =
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.string(1..10, Codepoint.az())) { lower ->
                assertThat(isAcceptable(lower)).isTrue()
                assertThat(isAcceptable(lower.replaceFirstChar { it.uppercaseChar() })).isFalse()
            }
        }

    @Test
    fun `difficulty is 0_5 at rank 1 length 5 (ADR-0013 calibration target)`() {
        assertThat(difficulty(rank = 1, length = 5).toDouble()).isCloseTo(0.5, 1e-6)
    }

    @Test
    fun `difficulty grows monotonically in rank for fixed length`() {
        val (d1, d10, d100) = listOf(1, 10, 100).map { difficulty(it, length = 5) }
        assertThat(d10).isGreaterThan(d1)
        assertThat(d100).isGreaterThan(d10)
    }

    @Test
    fun `difficulty grows monotonically in length for fixed rank`() {
        val (d3, d6, d9) = listOf(3, 6, 9).map { difficulty(rank = 1, length = it) }
        assertThat(d6).isGreaterThan(d3)
        assertThat(d9).isGreaterThan(d6)
    }
}
