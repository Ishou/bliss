package com.bliss.survey.application.ports

import java.util.UUID

fun interface IdGenerator {
    fun next(): UUID
}
