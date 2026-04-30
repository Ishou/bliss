package com.bliss.grid.worker

import org.slf4j.MDC
import java.util.UUID

private const val CORRELATION_ID_KEY = "correlation_id"

/** Tags every log line in [block] with a fresh `correlation_id` MDC entry, then clears it. */
internal inline fun <T> withCorrelationId(block: () -> T): T {
    MDC.put(CORRELATION_ID_KEY, UUID.randomUUID().toString())
    try {
        return block()
    } finally {
        MDC.remove(CORRELATION_ID_KEY)
    }
}
