package com.bliss.grid.domain.model

data class Word private constructor(
    val text: String,
    val definition: String,
) {
    init {
        require(text.isNotEmpty()) { "Word text must not be empty" }
        require(text.all { it in 'A'..'Z' }) { "Word text must be A-Z, was '$text'" }
    }

    companion object {
        operator fun invoke(
            text: String,
            definition: String,
        ): Word = Word(text.uppercase(), definition)
    }
}
