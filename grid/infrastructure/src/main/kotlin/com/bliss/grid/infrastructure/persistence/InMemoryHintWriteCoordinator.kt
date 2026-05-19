package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintWriteCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory [HintWriteCoordinator]; uses a per-user [Mutex] to mirror advisory-lock serialisation. */
class InMemoryHintWriteCoordinator : HintWriteCoordinator {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    override suspend fun <T> withUserLock(
        userId: UUID,
        block: suspend (Connection) -> T,
    ): T {
        val mutex = locks.computeIfAbsent(userId) { Mutex() }
        return mutex.withLock { block(STUB_CONNECTION) }
    }

    companion object {
        private val STUB_CONNECTION: Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ -> error("InMemoryHintWriteCoordinator connection stub does not support ${method.name}") } as Connection
    }
}
