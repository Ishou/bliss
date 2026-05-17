package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.UserProviderRepository
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.provider.toProvider
import com.bliss.identity.infrastructure.provider.toWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [UserProviderRepository]. JDBC is blocking; every method wraps its
 * calls in `withContext(Dispatchers.IO)` to keep the suspend port honest under
 * coroutine scopes (Ktor route handlers run on a limited dispatcher).
 */
class PostgresUserProviderRepository(
    private val dataSource: DataSource,
) : UserProviderRepository {
    override suspend fun link(userProvider: UserProvider): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, userProvider.userId.value)
                    stmt.setString(2, userProvider.provider.toWire())
                    stmt.setString(3, userProvider.subject.value)
                    stmt.setString(4, userProvider.emailAtLink)
                    stmt.setObject(5, userProvider.linkedAt)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findByProviderAndSubject(
        provider: Provider,
        subject: Subject,
    ): UserProvider? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_BY_PROVIDER_SUBJECT_SQL).use { stmt ->
                    stmt.setString(1, provider.toWire())
                    stmt.setString(2, subject.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toUserProvider() else null }
                }
            }
        }

    override suspend fun listForUser(userId: UserId): List<UserProvider> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_FOR_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeQuery().use { rs ->
                        val results = mutableListOf<UserProvider>()
                        while (rs.next()) results.add(rs.toUserProvider())
                        results
                    }
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

    private fun ResultSet.toUserProvider(): UserProvider =
        UserProvider(
            userId = UserId(getObject("user_id", UUID::class.java)),
            provider = getString("provider").toProvider(),
            subject = Subject.of(getString("subject")),
            emailAtLink = getString("email_at_link"),
            linkedAt = getObject("linked_at", Instant::class.java),
        )

    companion object {
        private const val INSERT_SQL =
            "INSERT INTO identity_user_providers (user_id, provider, subject, email_at_link, linked_at) VALUES (?, ?, ?, ?, ?)"
        private const val SELECT_BY_PROVIDER_SUBJECT_SQL =
            "SELECT user_id, provider, subject, email_at_link, linked_at FROM identity_user_providers WHERE provider = ? AND subject = ?"
        private const val SELECT_FOR_USER_SQL =
            "SELECT user_id, provider, subject, email_at_link, linked_at FROM identity_user_providers WHERE user_id = ?"
        private const val DELETE_FOR_USER_SQL =
            "DELETE FROM identity_user_providers WHERE user_id = ?"
    }
}
