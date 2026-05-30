package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [UserRepository]. JDBC is blocking; every method wraps its
 * calls in `withContext(Dispatchers.IO)` to keep the suspend port honest under
 * coroutine scopes (Ktor route handlers run on a limited dispatcher).
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
                    stmt.setObject(3, user.createdAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.setObject(4, user.lastSeenAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.setString(5, user.role.wire)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findById(id: UserId): User? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_SQL).use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
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
                    stmt.setObject(1, at.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun updateDisplayName(
        id: UserId,
        name: DisplayName,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_DISPLAY_NAME_SQL).use { stmt ->
                    stmt.setString(1, name.value)
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun updateRole(
        id: UserId,
        role: Role,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_ROLE_SQL).use { stmt ->
                    stmt.setString(1, role.wire)
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

    /** DisplayName.of re-validates at read time — out-of-band corrupt rows throw here by design. */
    private fun ResultSet.toUser(): User =
        User(
            id = UserId(getObject("user_id", UUID::class.java)),
            displayName = DisplayName.of(getString("display_name")),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            lastSeenAt = getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
            role = Role.fromWire(getString("role")),
        )

    companion object {
        private const val INSERT_SQL =
            "INSERT INTO identity_users (user_id, display_name, created_at, last_seen_at, role) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING"
        private const val SELECT_SQL =
            "SELECT user_id, display_name, created_at, last_seen_at, role FROM identity_users WHERE user_id = ?"
        private const val UPDATE_LAST_SEEN_SQL =
            "UPDATE identity_users SET last_seen_at = ? WHERE user_id = ?"
        private const val UPDATE_DISPLAY_NAME_SQL =
            "UPDATE identity_users SET display_name = ? WHERE user_id = ?"
        private const val UPDATE_ROLE_SQL =
            "UPDATE identity_users SET role = ? WHERE user_id = ?"
        private const val DELETE_SQL = "DELETE FROM identity_users WHERE user_id = ?"
    }
}
