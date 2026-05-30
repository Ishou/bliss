package com.bliss.survey.infrastructure.persistence

import java.sql.Connection
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Carries the in-flight transactional connection across coroutine boundaries. */
internal class TxConnection(
    val connection: Connection,
) : AbstractCoroutineContextElement(TxConnection) {
    companion object Key : CoroutineContext.Key<TxConnection>
}

/** Returns the ambient tx connection if one is bound, otherwise borrows a fresh pooled one; does NOT close the ambient connection. */
internal suspend inline fun <T> withTxConnection(
    dataSource: DataSource,
    block: (Connection) -> T,
): T {
    val ambient = coroutineContext[TxConnection]?.connection
    return if (ambient != null) block(ambient) else dataSource.connection.use(block)
}
