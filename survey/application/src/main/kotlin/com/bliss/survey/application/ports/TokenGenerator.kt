package com.bliss.survey.application.ports

fun interface TokenGenerator {
    // Returns a fresh high-entropy, URL-safe capability token (~256 bits).
    fun newToken(): String
}
