package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.TransactionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class PgTransactionManager(
    private val dataSource: DataSource,
) : TransactionManager {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val result = withContext(TxConnection(conn)) { block() }
                    conn.commit()
                    result
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }
}
