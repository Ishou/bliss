package com.bliss.survey.application.ports

interface TransactionManager {
    /** Runs [block] inside one DB transaction. Commits on normal return, rolls back on throw. Block must not fan out via concurrent child coroutines — the ambient [java.sql.Connection] is not thread-safe. */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
