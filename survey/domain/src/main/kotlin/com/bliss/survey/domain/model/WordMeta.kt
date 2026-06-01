package com.bliss.survey.domain.model

import java.time.Instant

data class WordMeta(
    val mot: String,
    val subTags: List<String>,
    val senseInventory: List<String>,
    val updatedAt: Instant,
) {
    init {
        require(mot.isNotBlank()) { "mot must be non-blank" }
        require(subTags.all { it.isNotBlank() }) {
            "subTags must not contain blank entries"
        }
        require(senseInventory.all { it.isNotBlank() }) {
            "senseInventory must not contain blank entries"
        }
        require(subTags.all { it.length <= MAX_SUB_TAG_LENGTH }) {
            "subTags entries bounded to $MAX_SUB_TAG_LENGTH chars (ADR-0061)"
        }
        require(senseInventory.all { it.length <= MAX_SENSE_LENGTH }) {
            "senseInventory entries bounded to $MAX_SENSE_LENGTH chars (ADR-0061)"
        }
    }

    companion object {
        const val MAX_SUB_TAG_LENGTH = 40
        const val MAX_SENSE_LENGTH = 80
    }
}
