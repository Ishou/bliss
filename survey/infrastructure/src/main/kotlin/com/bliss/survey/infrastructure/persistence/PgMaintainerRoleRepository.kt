package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed durable cache of cross-context role state (Spec B). */
class PgMaintainerRoleRepository(
    private val dataSource: DataSource,
) : MaintainerRoleRepository {
    override suspend fun find(userId: UserId): MaintainerRole? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(FIND_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            MaintainerRole(
                                userId = userId,
                                role = rs.getString("role"),
                                changedAt = rs.getTimestamp("changed_at").toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    override suspend fun upsert(role: MaintainerRole): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    stmt.setObject(1, role.userId.value)
                    stmt.setString(2, role.role)
                    stmt.setTimestamp(3, Timestamp.from(role.changedAt))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun delete(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(DELETE_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun listMaintainers(): List<UserId> =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(LIST_MAINTAINERS_SQL).use { stmt ->
                    val out = mutableListOf<UserId>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) out += UserId(rs.getObject(1, UUID::class.java))
                    }
                    out
                }
            }
        }

    private companion object {
        const val FIND_SQL = "SELECT role, changed_at FROM maintainer_roles WHERE user_id = ?"
        const val UPSERT_SQL =
            """
            INSERT INTO maintainer_roles (user_id, role, changed_at)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET role = excluded.role, changed_at = excluded.changed_at
            """
        const val DELETE_SQL = "DELETE FROM maintainer_roles WHERE user_id = ?"
        const val LIST_MAINTAINERS_SQL = "SELECT user_id FROM maintainer_roles WHERE role = 'maintainer'"
    }
}
