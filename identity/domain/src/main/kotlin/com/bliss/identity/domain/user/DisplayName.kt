package com.bliss.identity.domain.user

/**
 * Player-chosen display name. 1-30 chars after trimming. Not unique
 * (no enumeration vector — see ADR-0045). Never pulled from an IdP.
 */
@JvmInline
value class DisplayName private constructor(
    val value: String,
) {
    companion object {
        private const val MIN_LENGTH = 1
        private const val MAX_LENGTH = 30

        fun of(raw: String): DisplayName {
            val trimmed = raw.trim()
            require(trimmed.length in MIN_LENGTH..MAX_LENGTH) {
                "Display name must be $MIN_LENGTH-$MAX_LENGTH characters; got ${trimmed.length}."
            }
            return DisplayName(trimmed)
        }
    }
}
