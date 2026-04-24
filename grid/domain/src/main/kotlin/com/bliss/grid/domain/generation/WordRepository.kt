package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word

interface WordRepository {
    fun findByLength(length: Int): List<Word>

    fun findByLengthAndPattern(
        length: Int,
        pattern: Map<Int, Char>,
    ): List<Word>
}
