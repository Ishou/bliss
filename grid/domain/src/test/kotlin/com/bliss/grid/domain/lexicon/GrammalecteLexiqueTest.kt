package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GrammalecteLexiqueTest {
    private fun parse(vararg lines: String) = parseGrammalecteLexique(lines.asSequence())

    @Test
    fun `returns single row for a normal entry`() {
        val result = parse("# comment", "id\tFlexion\tLemme\tTotal occurrences", "1\tchat\tchat\t5000")
        assertThat(result.size).isEqualTo(1)
        val row = result["chat"]!!
        assertThat(row.lemma).isEqualTo("chat")
        assertThat(row.occurrences).isEqualTo(5000L)
    }

    @Test
    fun `deduplicates keeping highest occurrence for same surface form`() {
        val result = parse(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tchat\tchat nom\t3000",
            "2\tchat\tchat adj\t5000",
            "3\tchat\tchat inv\t100",
        )
        assertThat(result.size).isEqualTo(1)
        val row = result["chat"]!!
        assertThat(row.lemma).isEqualTo("chat adj")
        assertThat(row.occurrences).isEqualTo(5000L)
    }

    @Test
    fun `skips malformed rows with too few columns`() {
        val result = parse(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tchat\t",
            "2\tchien\tchien\t4000",
        )
        assertThat(result.size).isEqualTo(1)
        assertThat(result["chien"]).isEqualTo(GrammalecteEntry("chien", "chien", 4000L))
        assertThat(result["chat"]).isNull()
    }

    @Test
    fun `skips capitalized surface forms via isAcceptable`() {
        val result = parse(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tParis\tparis\t99000",
            "2\trive\trive\t1200",
        )
        assertThat(result.size).isEqualTo(1)
        assertThat(result.containsKey("paris")).isEqualTo(false)
        assertThat(result["rive"]).isEqualTo(GrammalecteEntry("rive", "rive", 1200L))
    }

    @Test
    fun `skips comment lines and header before column header`() {
        val result = parse(
            "# Grammalecte lexique",
            "# version 7.7",
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\tmer\tmer\t8000",
        )
        assertThat(result.size).isEqualTo(1)
        assertThat(result["mer"]!!.occurrences).isEqualTo(8000L)
    }

    @Test
    fun `returns empty map when no valid header found`() {
        val result = parse("not\ta\tvalid\theader", "1\tchat\tchat\t5000")
        assertThat(result).isEmpty()
    }

    @Test
    fun `lowercases both flexion and lemma`() {
        val result = parse(
            "id\tFlexion\tLemme\tTotal occurrences",
            "1\taimer\tAimer\t2500",
        )
        assertThat(result["aimer"]!!.lemma).isEqualTo("aimer")
    }

    @Test
    fun `fails when header missing required columns`() {
        assertThrows<IllegalArgumentException> {
            parse("id\tFlexion\tAutreColonne", "1\tchat\tchat")
        }
    }
}
