package com.bliss.survey.application.ports

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}
