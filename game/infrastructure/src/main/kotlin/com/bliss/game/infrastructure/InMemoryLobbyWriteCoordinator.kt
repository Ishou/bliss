package com.bliss.game.infrastructure

import com.bliss.game.application.lobby.LobbyWriteCoordinator
import com.bliss.game.domain.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/** In-memory [LobbyWriteCoordinator]; uses a per-user [Mutex] to mirror advisory-lock serialisation. */
class InMemoryLobbyWriteCoordinator : LobbyWriteCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun <T> withUserLock(
        userId: UserId,
        block: suspend (Connection) -> T,
    ): T {
        val mutex = locks.computeIfAbsent(userId.value) { Mutex() }
        return mutex.withLock { block(STUB_CONNECTION) }
    }

    companion object {
        private val STUB_CONNECTION: Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ -> error("InMemoryLobbyWriteCoordinator connection stub does not support ${method.name}") } as Connection
    }
}
