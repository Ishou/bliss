package com.bliss.grid.worker.importer

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ImportGrammalecteCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private val cmd = ImportGrammalecteCommand()

    private fun lexiqueFile(content: String): Path {
        val file = tempDir.resolve("lexique.txt")
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        return file
    }

    @Test
    fun `parse returns single row for a normal entry`() {
        val file =
            lexiqueFile(
                "# comment\nid\tFlexion\tLemme\tTotal occurrences\n1\tchat\tchat\t5000\n",
            )
        val result = cmd.parse(file)
        assertThat(result.size).isEqualTo(1)
        val row = result["chat"]!!
        assertThat(row.lemma).isEqualTo("chat")
        assertThat(row.occurrences).isEqualTo(5000L)
    }

    @Test
    fun `parse deduplicates keeping highest occurrence for same surface form`() {
        val file =
            lexiqueFile(
                "id\tFlexion\tLemme\tTotal occurrences\n" +
                    "1\tchat\tchat nom\t3000\n" +
                    "2\tchat\tchat adj\t5000\n" +
                    "3\tchat\tchat inv\t100\n",
            )
        val result = cmd.parse(file)
        assertThat(result.size).isEqualTo(1)
        val row = result["chat"]!!
        assertThat(row.lemma).isEqualTo("chat adj")
        assertThat(row.occurrences).isEqualTo(5000L)
    }

    @Test
    fun `parse skips malformed rows with too few columns`() {
        val file =
            lexiqueFile(
                "id\tFlexion\tLemme\tTotal occurrences\n" +
                    "1\tchat\t\n" +
                    "2\tchien\tchien\t4000\n",
            )
        val result = cmd.parse(file)
        assertThat(result.size).isEqualTo(1)
        assertThat(result["chien"]).isEqualTo(ImportGrammalecteCommand.Row("chien", 4000L))
        assertThat(result["chat"]).isNull()
    }

    @Test
    fun `parse skips capitalized surface forms via isAcceptable`() {
        val file =
            lexiqueFile(
                "id\tFlexion\tLemme\tTotal occurrences\n" +
                    "1\tParis\tparis\t99000\n" +
                    "2\trive\trive\t1200\n",
            )
        val result = cmd.parse(file)
        assertThat(result.size).isEqualTo(1)
        assertThat(result.containsKey("paris")).isEqualTo(false)
        assertThat(result["rive"]).isEqualTo(ImportGrammalecteCommand.Row("rive", 1200L))
    }

    @Test
    fun `parse skips comment lines and header before column header`() {
        val file =
            lexiqueFile(
                "# Grammalecte lexique\n" +
                    "# version 7.7\n" +
                    "id\tFlexion\tLemme\tTotal occurrences\n" +
                    "1\tmer\tmer\t8000\n",
            )
        val result = cmd.parse(file)
        assertThat(result.size).isEqualTo(1)
        assertThat(result["mer"]!!.occurrences).isEqualTo(8000L)
    }

    @Test
    fun `parse returns empty map when no valid header found`() {
        val file = lexiqueFile("not\ta\tvalid\theader\n1\tchat\tchat\t5000\n")
        val result = cmd.parse(file)
        assertThat(result).isEmpty()
    }

    @Test
    fun `parse lowercases both flexion and lemma`() {
        val file =
            lexiqueFile(
                "id\tFlexion\tLemme\tTotal occurrences\n" +
                    "1\taimer\tAimer\t2500\n",
            )
        val result = cmd.parse(file)
        assertThat(result["aimer"]!!.lemma).isEqualTo("aimer")
    }

    @Test
    fun `parse fails when header missing required columns`() {
        val file = lexiqueFile("id\tFlexion\tAutreColonne\n1\tchat\tchat\n")
        assertThrows<IllegalArgumentException> {
            cmd.parse(file)
        }
    }
}
