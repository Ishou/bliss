package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ClueCandidateTest {
    private val anyWord = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `accepts a minimal valid candidate`() {
        val c =
            ClueCandidate(
                wordId = anyWord,
                source = ClueSource.CURATED,
                clueText = "Bagnole",
            )
        assertThat(c.senseIndex).isNull()
        assertThat(c.confidence).isNull()
        assertThat(c.modelVersion).isNull()
    }

    @Test
    fun `rejects blank source`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(wordId = anyWord, source = "", clueText = "x")
        }
        assertThrows<IllegalArgumentException> {
            ClueCandidate(wordId = anyWord, source = "   ", clueText = "x")
        }
    }

    @Test
    fun `rejects empty clue text`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(wordId = anyWord, source = ClueSource.CURATED, clueText = "")
        }
    }

    @Test
    fun `rejects blank clue text`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(wordId = anyWord, source = ClueSource.CURATED, clueText = "   ")
        }
    }

    @Test
    fun `rejects clue text longer than 80`() {
        val tooLong = "a".repeat(81)
        assertThrows<IllegalArgumentException> {
            ClueCandidate(wordId = anyWord, source = ClueSource.CURATED, clueText = tooLong)
        }
    }

    @Test
    fun `accepts clue text of length 1 and 80 (boundary)`() {
        ClueCandidate(wordId = anyWord, source = ClueSource.CURATED, clueText = "a")
        ClueCandidate(wordId = anyWord, source = ClueSource.CURATED, clueText = "a".repeat(80))
    }

    @Test
    fun `rejects negative senseIndex`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(
                wordId = anyWord,
                source = ClueSource.CURATED,
                clueText = "x",
                senseIndex = -1,
            )
        }
    }

    @Test
    fun `accepts zero senseIndex`() {
        val c =
            ClueCandidate(
                wordId = anyWord,
                source = ClueSource.DBNARY_SYNONYM,
                clueText = "x",
                senseIndex = 0,
            )
        assertThat(c.senseIndex).isEqualTo(0)
    }

    @Test
    fun `rejects confidence outside 0_to_1`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(
                wordId = anyWord,
                source = ClueSource.CURATED,
                clueText = "x",
                confidence = -0.01,
            )
        }
        assertThrows<IllegalArgumentException> {
            ClueCandidate(
                wordId = anyWord,
                source = ClueSource.CURATED,
                clueText = "x",
                confidence = 1.01,
            )
        }
    }

    @Test
    fun `accepts confidence at the 0 and 1 boundaries`() {
        ClueCandidate(
            wordId = anyWord,
            source = ClueSource.CURATED,
            clueText = "x",
            confidence = 0.0,
        )
        ClueCandidate(
            wordId = anyWord,
            source = ClueSource.CURATED,
            clueText = "x",
            confidence = 1.0,
        )
    }

    @Test
    fun `rejects blank modelVersion when set`() {
        assertThrows<IllegalArgumentException> {
            ClueCandidate(
                wordId = anyWord,
                source = "mistral-7b-base",
                clueText = "x",
                modelVersion = "  ",
            )
        }
    }

    @Test
    fun `accepts modelVersion when set`() {
        val c =
            ClueCandidate(
                wordId = anyWord,
                source = "mistral-7b-base",
                clueText = "x",
                modelVersion = "mistral-7b-instruct-v0.3",
            )
        assertThat(c.modelVersion).isEqualTo("mistral-7b-instruct-v0.3")
    }
}
