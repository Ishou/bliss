package com.bliss.game.api

import com.bliss.game.application.ports.WebSocketRevocationBroadcaster
import com.bliss.game.domain.UserId
import org.slf4j.LoggerFactory

/** Thin adapter that delegates forced-disconnect for a given user to [SessionManager.closeAllForUser]. */
class WebSocketRevocationBroadcasterAdapter(
    private val sessions: SessionManager,
) : WebSocketRevocationBroadcaster {
    private val log = LoggerFactory.getLogger(WebSocketRevocationBroadcasterAdapter::class.java)

    override suspend fun disconnectAllForUser(userId: UserId) {
        val closed = sessions.closeAllForUser(userId)
        if (closed > 0) {
            log.info("ws.revoke.user.deleted userId={} closed_sockets={}", userId.value, closed)
        }
    }
}
