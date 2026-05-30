package com.bliss.survey.application

import java.security.MessageDigest

fun sha256(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
