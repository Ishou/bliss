package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.lexicon.CURATED_LICENSE
import com.bliss.grid.domain.lexicon.CURATED_SOURCE
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileCuratedSourceReaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns empty when no file exists for language`() {
        val reader = FileCuratedSourceReader(tempDir)
        assertThat(reader.rows("fr").toList()).isEmpty()
    }

    @Test
    fun `aggregates rows from every csv file in the directory`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ne,fr,2,100000,,Point cardinal,bliss,CC0-1.0
            """.trimIndent(),
        )
        write(
            "roman2.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            MM,fr,2,100000,,M + M,bliss,CC0-1.0
            """.trimIndent(),
        )
        val rows = FileCuratedSourceReader(tempDir).rows("fr").toList()
        assertThat(rows.map { it.word }).containsExactlyInAnyOrder("ne", "MM")
    }

    @Test
    fun `parses curated rows and stamps source plus license`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ne,fr,2,100000,,Point cardinal,bliss,CC0-1.0
            fe,fr,2,100000,,Symbole du fer,bliss,CC0-1.0
            """.trimIndent(),
        )
        val rows = FileCuratedSourceReader(tempDir).rows("fr").toList()

        assertThat(rows.map { it.word }).containsExactlyInAnyOrder("ne", "fe")
        rows.forEach {
            assertThat(it.source).isEqualTo(CURATED_SOURCE)
            assertThat(it.sourceLicense).isEqualTo(CURATED_LICENSE)
            assertThat(it.length).isEqualTo(it.word.length)
        }
    }

    @Test
    fun `lemma falls back to the word when column is absent or blank`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license,lemma
            va,fr,2,100000,,Forme verbale,bliss,CC0-1.0,aller
            ne,fr,2,100000,,Point cardinal,bliss,CC0-1.0,
            """.trimIndent(),
        )
        write(
            "no-lemma.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ag,fr,2,100000,,Symbole de l'argent,bliss,CC0-1.0
            """.trimIndent(),
        )
        val rows = FileCuratedSourceReader(tempDir).rows("fr").associateBy { it.word }
        assertThat(rows.getValue("va").lemma).isEqualTo("aller")
        assertThat(rows.getValue("ne").lemma).isEqualTo("ne")
        assertThat(rows.getValue("ag").lemma).isEqualTo("ag")
    }

    @Test
    fun `length is recomputed from the word ignoring the file`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            mr,fr,99,100000,,Monsieur,bliss,CC0-1.0
            """.trimIndent(),
        )
        val row = FileCuratedSourceReader(tempDir).rows("fr").single()
        assertThat(row.length).isEqualTo(2)
    }

    @Test
    fun `filters by language column`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ne,fr,2,100000,,Point cardinal,bliss,CC0-1.0
            cat,en,3,100000,,English cat,bliss,CC0-1.0
            """.trimIndent(),
        )
        val rows = FileCuratedSourceReader(tempDir).rows("fr").toList()
        assertThat(rows.map { it.word }).containsExactlyInAnyOrder("ne")
    }

    @Test
    fun `blank clue fails fast`() {
        write(
            "fr.csv",
            """
            word,language,length,frequency,difficulty,clue,source,source_license
            ne,fr,2,100000,,,bliss,CC0-1.0
            """.trimIndent(),
        )
        val reader = FileCuratedSourceReader(tempDir)
        assertThrows<IllegalArgumentException> { reader.rows("fr").toList() }
    }

    private fun write(
        name: String,
        content: String,
    ) {
        Files.writeString(tempDir.resolve(name), content + "\n")
    }
}
