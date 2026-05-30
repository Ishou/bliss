package com.bliss.survey.application.ports

interface TransactionManager {
    /** Runs [block] inside one DB transaction. Commits on normal return, rolls back on throw. */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
