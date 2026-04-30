package com.bliss.grid.domain.lexicon

/** Surface form + raw occurrence count from a frequency corpus. */
data class WordFrequency(
    val word: String,
    val count: Long,
)

/**
 * Pure parser for the `<word> <count>` line format consumed by `import-frequencies`.
 * Skips empty lines and `#` comments. Lines with no space separator, no count, or a
 * non-numeric count are dropped silently — the corpus is large enough that a few
 * malformed rows shouldn't fail the whole run. Words are lowercased.
 */
fun parseFrequencies(lines: Sequence<String>): List<WordFrequency> =
    lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val sep = line.indexOf(' ')
            if (sep <= 0 || sep == line.lastIndex) return@mapNotNull null
            val word = line.substring(0, sep)
            val count = line.substring(sep + 1).trim().toLongOrNull() ?: return@mapNotNull null
            WordFrequency(word.lowercase(), count)
        }.toList()
