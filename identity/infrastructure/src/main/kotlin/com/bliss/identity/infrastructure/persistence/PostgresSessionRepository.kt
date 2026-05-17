package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.SessionRepository
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed [SessionRepository]; JDBC blocks so every method dispatches on [kotlinx.coroutines.Dispatchers.IO]. */
class PostgresSessionRepository(
    private val dataSource: DataSource,
) : SessionRepository {
    override suspend fun create(session: Session): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, session.id.value)
                    stmt.setObject(2, session.userId.value)
                    stmt.setObject(3, session.createdAt.atOffset(ZoneOffset.UTC))
                    stmt.setObject(4, session.lastSeenAt.atOffset(ZoneOffset.UTC))
                    val revokedAt = session.revokedAt
                    if (revokedAt != null) {
                        stmt.setObject(5, revokedAt.atOffset(ZoneOffset.UTC))
                    } else {
                        stmt.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
                    }
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findById(id: SessionId): Session? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
                }
            }
        }

    override suspend fun revoke(
        id: SessionId,
        at: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(REVOKE_SQL).use { stmt ->
                    stmt.setObject(1, at.atOffset(ZoneOffset.UTC))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun deleteForUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_FOR_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    private fun ResultSet.toSession(): Session =
        Session(
            id = SessionId(getObject("session_id", UUID::class.java)),
            userId = UserId(getObject("user_id", UUID::class.java)),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            lastSeenAt = getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
            revokedAt = getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
        )

    companion object {
        private const val INSERT_SQL =
            "INSERT INTO identity_sessions (session_id, user_id, created_at, last_seen_at, revoked_at) VALUES (?, ?, ?, ?, ?)"
        private const val SELECT_BY_ID_SQL =
            "SELECT session_id, user_id, created_at, last_seen_at, revoked_at FROM identity_sessions WHERE session_id = ?"
        private const val REVOKE_SQL =
            "UPDATE identity_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL"
        private const val DELETE_FOR_USER_SQL =
            "DELETE FROM identity_sessions WHERE user_id = ?"
    }
}
