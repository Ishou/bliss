package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed [ProposedByRepository] for rater-proposed corpus authorship (spec §10.2). */
class PgProposedByRepository(
    private val dataSource: DataSource,
) : ProposedByRepository {
    override suspend fun insert(
        itemId: ItemId,
        userId: UserId,
        optedOut: Boolean,
    ): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, itemId.value)
                    stmt.setObject(2, userId.value)
                    stmt.setBoolean(3, optedOut)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun setOptOut(
        userId: UserId,
        optedOut: Boolean,
    ): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(SET_OPT_OUT_SQL).use { stmt ->
                    stmt.setBoolean(1, optedOut)
                    stmt.setObject(2, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun listOptedOutByUser(userId: UserId): List<ItemId> =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(LIST_OPTED_OUT_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    val out = mutableListOf<ItemId>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) out += ItemId(rs.getObject(1, UUID::class.java))
                    }
                    out
                }
            }
        }

    override suspend fun deleteByUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(DELETE_BY_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    private companion object {
        const val INSERT_SQL =
            "INSERT INTO proposed_by (proposed_item_id, user_id, opted_out) VALUES (?, ?, ?)"
        const val SET_OPT_OUT_SQL = "UPDATE proposed_by SET opted_out = ? WHERE user_id = ?"
        const val LIST_OPTED_OUT_SQL =
            "SELECT proposed_item_id FROM proposed_by WHERE user_id = ? AND opted_out = TRUE"
        const val DELETE_BY_USER_SQL = "DELETE FROM proposed_by WHERE user_id = ?"
    }
}
