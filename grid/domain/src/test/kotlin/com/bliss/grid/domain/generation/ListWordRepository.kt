package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word

/**
 * Minimal WordRepository for domain tests. Not production code — lives in test sources only.
 */
class ListWordRepository(
    private val words: List<Word>,
) : WordRepository {
    override fun findByLength(length: Int): List<Word> = words.filter { it.text.length == length }

    override fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word> =
        words.filter { word ->
            word.text.length == length && pattern.all { (i, ch) -> word.text[i] == ch }
        }
}
