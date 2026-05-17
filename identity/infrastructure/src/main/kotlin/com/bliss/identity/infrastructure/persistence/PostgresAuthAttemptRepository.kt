package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.provider.toProvider
import com.bliss.identity.infrastructure.provider.toWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [AuthAttemptRepository]. JDBC is blocking; every method wraps its
 * calls in `withContext(Dispatchers.IO)` to keep the suspend port honest under
 * coroutine scopes (Ktor route handlers run on a limited dispatcher).
 */
class PostgresAuthAttemptRepository(
    private val dataSource: DataSource,
) : AuthAttemptRepository {
    override suspend fun create(attempt: AuthAttempt): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, attempt.id.value)
                    stmt.setString(2, attempt.state.value)
                    stmt.setString(3, attempt.pkceVerifier.value)
                    stmt.setString(4, attempt.provider.toWire())
                    stmt.setString(5, attempt.returnTo)
                    val linkToUserId = attempt.linkToUserId
                    if (linkToUserId != null) {
                        stmt.setObject(6, linkToUserId.value)
                    } else {
                        stmt.setNull(6, Types.OTHER)
                    }
                    stmt.setObject(7, attempt.expiresAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findByState(state: State): AuthAttempt? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_BY_STATE_SQL).use { stmt ->
                    stmt.setString(1, state.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toAuthAttempt() else null }
                }
            }
        }

    override suspend fun deleteByState(state: State): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_BY_STATE_SQL).use { stmt ->
                    stmt.setString(1, state.value)
                    stmt.executeUpdate()
                }
            }
        }

    private fun ResultSet.toAuthAttempt(): AuthAttempt {
        val linkToUserIdRaw = getObject("link_to_user_id", UUID::class.java)
        return AuthAttempt(
            id = AuthAttemptId(getObject("attempt_id", UUID::class.java)),
            state = State.of(getString("state")),
            pkceVerifier = PkceVerifier.of(getString("pkce_verifier")),
            provider = getString("provider").toProvider(),
            returnTo = getString("return_to"),
            linkToUserId = linkToUserIdRaw?.let { UserId(it) },
            expiresAt = getObject("expires_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    companion object {
        private const val INSERT_SQL =
            "INSERT INTO identity_auth_attempts " +
                "(attempt_id, state, pkce_verifier, provider, return_to, link_to_user_id, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        private const val SELECT_BY_STATE_SQL =
            "SELECT attempt_id, state, pkce_verifier, provider, return_to, link_to_user_id, expires_at " +
                "FROM identity_auth_attempts WHERE state = ?"
        private const val DELETE_BY_STATE_SQL =
            "DELETE FROM identity_auth_attempts WHERE state = ?"
    }
}
