package com.bliss.survey.application.ports

import kotlin.random.Random

fun interface RandomFactory {
    fun create(): Random
}
