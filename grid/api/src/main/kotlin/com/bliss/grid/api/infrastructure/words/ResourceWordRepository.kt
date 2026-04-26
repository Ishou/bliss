package com.bliss.grid.api.infrastructure.words

import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Classpath-backed [WordRepository] for v1 puzzle generation.
 *
 * Hosts the API layer's adapter to the domain word port (ADR-0001 §1).
 * Words live in `src/main/resources/words/<lang>.json` so the data ships
 * with the deployable jar — no external service, no network, no migrations.
 *
 * The wire shape matches the resource file:
 *
 * ```json
 * [ { "word": "CHAT", "clue": "Felin domestique" }, ... ]
 * ```
 *
 * Words are uppercase A-Z (matches the domain `LetterCell` invariant); clues
 * are short French definitions rendered inside the puzzle's `definition`
 * cell.
 */
class ResourceWordRepository(
    words: List<Word>,
) : WordRepository {
    private val byLength: Map<Int, List<Word>> = words.groupBy { it.text.length }

    override fun findByLength(length: Int): List<Word> = byLength[length].orEmpty()

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> =
        findByLength(length).filter { word ->
            pattern.all { (i, ch) -> i in word.text.indices && word.text[i] == ch }
        }

    companion object {
        private const val FRENCH_RESOURCE_PATH = "/words/fr.json"

        /**
         * Loads the bundled French word list from the JVM classpath.
         *
         * Bias: the curated list keeps words 3-7 letters; longer entries would
         * inflate the grid generator's branching factor without a backing
         * mots-fléchés frequency model.
         */
        fun frenchFromClasspath(): ResourceWordRepository = fromClasspath(FRENCH_RESOURCE_PATH)

        fun fromClasspath(path: String): ResourceWordRepository {
            val stream =
                ResourceWordRepository::class.java.getResourceAsStream(path)
                    ?: error("Word resource not found on classpath: $path")
            val raw = stream.use { it.readAllBytes().toString(Charsets.UTF_8) }
            val entries = Json.decodeFromString(JsonEntries.serializer(), """{"items": $raw}""").items
            val words =
                entries.map { entry ->
                    Word(text = entry.word, definition = entry.clue)
                }
            return ResourceWordRepository(words)
        }
    }

    @Serializable
    private data class JsonEntries(
        val items: List<JsonEntry>,
    )

    @Serializable
    private data class JsonEntry(
        val word: String,
        val clue: String,
    )
}
