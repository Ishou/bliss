package com.bliss.grid.worker.clues

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThanOrEqualTo
import org.junit.jupiter.api.Test

/** Lock the prompt against accidental edits (ADR-0013 §5: changes are ADR-class). */
class CluePromptTest {
    @Test
    fun `system prompt mentions the 18-char cap at least twice`() {
        val occurrences = "18".toRegex().findAll(SYSTEM_PROMPT).count()
        assertThat(occurrences).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `system prompt names the language and the role`() {
        // Cheap structural check — guards against a careless rename of the role.
        assertThat(SYSTEM_PROMPT).contains("français")
        assertThat(SYSTEM_PROMPT).contains("mots croisés")
    }

    @Test
    fun `few-shot block includes every example as a Mot - clue line`() {
        val block = fewShotBlock()
        FEW_SHOT_EXAMPLES.forEach { (word, clue) ->
            assertThat(block).contains("Mot : $word -> $clue")
        }
    }

    @Test
    fun `every few-shot clue respects the 18-char cap`() {
        FEW_SHOT_EXAMPLES.forEach { (_, clue) ->
            check(clue.length <= MAX_CLUE_CHARS) {
                "Few-shot clue '$clue' is ${clue.length} chars (>$MAX_CLUE_CHARS)"
            }
        }
    }

    @Test
    fun `retry message names the cap and embeds the word`() {
        val msg = retryMessage("chat")
        assertThat(msg).contains("18")
        assertThat(msg).contains("chat")
    }
}
