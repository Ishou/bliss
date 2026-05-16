package com.bliss.identity.domain.provider

enum class Provider {
    GOOGLE,
    APPLE,
    ;

    fun toWire(): String = name.lowercase()

    companion object {
        fun fromWire(wire: String): Provider =
            entries.firstOrNull { it.toWire() == wire }
                ?: throw IllegalArgumentException("Unknown provider: '$wire'.")
    }
}
