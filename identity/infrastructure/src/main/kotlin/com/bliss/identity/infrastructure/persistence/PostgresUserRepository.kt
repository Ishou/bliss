package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import javax.sql.DataSource

/**
 * Postgres-backed [UserRepository] for the identity context (ADR-0044).
 *
 * Every blocking JDBC call is wrapped in [withContext(Dispatchers.IO)] so
 * coroutine callers are never blocked on a thread-pool thread.
 *
 * Timestamps are read/written as [Timestamp] with an explicit UTC [Calendar]
 * to avoid JVM default-timezone drift.
 */
class PostgresUserRepository(
    private val dataSource: DataSource,
) : UserRepository {
    override suspend fun create(user: User): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, user.id.value)
                    stmt.setString(2, user.displayName.value)
                    stmt.setTimestamp(3, Timestamp.from(user.createdAt), UTC_CAL)
                    stmt.setTimestamp(4, Timestamp.from(user.lastSeenAt), UTC_CAL)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findById(id: UserId): User? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        User(
                            id = UserId(rs.getObject("user_id", java.util.UUID::class.java)),
                            displayName = DisplayName.of(rs.getString("display_name")),
                            createdAt = rs.getTimestamp("created_at", UTC_CAL).toInstant(),
                            lastSeenAt = rs.getTimestamp("last_seen_at", UTC_CAL).toInstant(),
                        )
                    }
                }
            }
        }

    override suspend fun updateLastSeenAt(
        id: UserId,
        at: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_LAST_SEEN_SQL).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(at), UTC_CAL)
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun delete(id: UserId): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_SQL).use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    companion object {
        private val UTC_CAL: Calendar
            get() = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        private const val INSERT_SQL =
            """
            INSERT INTO identity_users (user_id, display_name, created_at, last_seen_at)
            VALUES (?, ?, ?, ?)
            """

        private const val SELECT_BY_ID_SQL =
            """
            SELECT user_id, display_name, created_at, last_seen_at
            FROM identity_users
            WHERE user_id = ?
            """

        private const val UPDATE_LAST_SEEN_SQL =
            """
            UPDATE identity_users
            SET last_seen_at = ?
            WHERE user_id = ?
            """

        private const val DELETE_SQL =
            "DELETE FROM identity_users WHERE user_id = ?"
    }
}
