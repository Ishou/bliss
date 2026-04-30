package com.bliss.grid.worker.importer

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.lexicon.WordFrequency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ImportFrequenciesCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private val cmd = ImportFrequenciesCommand()

    private fun freqFile(content: String): Path {
        val file = tempDir.resolve("freq.txt")
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        return file
    }

    private fun pair(word: String, count: Long) = WordFrequency(word, count)

    @Test
    fun `parse returns word-count pairs for normal lines`() {
        val file = freqFile("chat 5000\nchien 4000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chat", 5000L), pair("chien", 4000L)))
    }

    @Test
    fun `parse skips comment lines`() {
        val file = freqFile("# comment\nchat 5000\n# another comment\nchien 4000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chat", 5000L), pair("chien", 4000L)))
    }

    @Test
    fun `parse skips empty lines`() {
        val file = freqFile("chat 5000\n\n   \nchien 4000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chat", 5000L), pair("chien", 4000L)))
    }

    @Test
    fun `parse skips lines with non-numeric count`() {
        val file = freqFile("chat abc\nchien 4000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chien", 4000L)))
    }

    @Test
    fun `parse skips lines with no space separator`() {
        val file = freqFile("chatsanspace\nchien 4000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chien", 4000L)))
    }

    @Test
    fun `parse lowercases words`() {
        val file = freqFile("Chat 5000\n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chat", 5000L)))
    }

    @Test
    fun `parse returns empty list for empty file`() {
        val file = freqFile("")
        assertThat(cmd.parse(file)).isEmpty()
    }

    @Test
    fun `parse trims surrounding whitespace from lines`() {
        val file = freqFile("  chat   5000  \n")
        assertThat(cmd.parse(file)).isEqualTo(listOf(pair("chat", 5000L)))
    }
}
