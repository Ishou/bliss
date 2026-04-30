package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParseGrammalecteLexiqueTest {
    private fun lines(vararg rows: String) = sequenceOf(*rows)

    @Test
    fun `parses a normal entry`() {
        val result = parseGrammalecteLexique(lines(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tchat\tchat\t5000",
        ))
        assertThat(result["chat"]).isEqualTo(GrammalecteEntry("chat", "chat", 5000L))
    }

    @Test
    fun `deduplicates keeping highest occurrence count`() {
        val result = parseGrammalecteLexique(lines(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tchat\tchat nom\t3000",
            "2\tchat\tchat adj\t5000",
            "3\tchat\tchat inv\t100",
        ))
        assertThat(result.size).isEqualTo(1)
        assertThat(result["chat"]).isEqualTo(GrammalecteEntry("chat", "chat adj", 5000L))
    }

    @Test
    fun `skips capitalized surface forms via isAcceptable`() {
        val result = parseGrammalecteLexique(lines(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tParis\tparis\t99000",
            "2\trive\trive\t1200",
        ))
        assertThat(result["paris"]).isNull()
        assertThat(result["rive"]).isEqualTo(GrammalecteEntry("rive", "rive", 1200L))
    }

    @Test
    fun `skips rows with too few columns`() {
        val result = parseGrammalecteLexique(lines(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tchat\t",
            "2\tchien\tchien\t4000",
        ))
        assertThat(result["chat"]).isNull()
        assertThat(result["chien"]).isEqualTo(GrammalecteEntry("chien", "chien", 4000L))
    }

    @Test
    fun `skips comment lines and detects header after them`() {
        val result = parseGrammalecteLexique(lines(
            "# Grammalecte lexique",
            "# version 7.7",
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tmer\tmer\t8000",
        ))
        assertThat(result["mer"]!!.occurrences).isEqualTo(8000L)
    }

    @Test
    fun `returns empty map when no valid header found`() {
        val result = parseGrammalecteLexique(lines("not\ta\tvalid\theader", "1\tchat\tchat\t5000"))
        assertThat(result).isEmpty()
    }

    @Test
    fun `throws when header has id and Flexion but missing required columns`() {
        assertThrows<IllegalArgumentException> {
            parseGrammalecteLexique(lines("id\tFlexion\tAutreColonne", "1\tchat\tchat"))
        }
    }

    @Test
    fun `lowercases both surface form and lemma`() {
        val result = parseGrammalecteLexique(lines(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\taimer\tAimer\t2500",
        ))
        assertThat(result["aimer"]!!.lemma).isEqualTo("aimer")
    }
}
