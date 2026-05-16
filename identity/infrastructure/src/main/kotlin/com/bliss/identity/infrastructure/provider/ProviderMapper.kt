package com.bliss.identity.infrastructure.provider

import com.bliss.identity.domain.provider.Provider

fun Provider.toWire(): String = name.lowercase()

fun String.toProvider(): Provider =
    Provider.entries.firstOrNull { it.toWire() == this }
        ?: throw IllegalArgumentException("Unknown provider: '$this'.")
