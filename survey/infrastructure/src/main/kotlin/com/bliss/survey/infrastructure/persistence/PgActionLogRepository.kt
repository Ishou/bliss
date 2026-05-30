package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.ActionLogRepository
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairRatingId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PgActionLogRepository(
    private val dataSource: DataSource,
) : ActionLogRepository {
    override suspend fun insert(action: SurveyAction): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, action.id.value)
                    stmt.setBytes(2, action.undoTokenHash)
                    stmt.setObjectOrNull(3, action.userId?.value)
                    stmt.setString(4, action.kind.name.lowercase())
                    stmt.setObject(5, action.campaignId.value)
                    stmt.setTimestamp(6, Timestamp.from(action.createdAt))
                    val ratingIds = action.createdRatingIds.map { it.value }.toTypedArray()
                    stmt.setArray(7, conn.createArrayOf("uuid", ratingIds))
                    stmt.setObjectOrNull(8, action.createdPairId?.value)
                    stmt.setObjectOrNull(9, action.createdItemId?.value)
                    stmt.setObjectOrNull(10, action.proposedItemId?.value)
                    stmt.setObjectOrNull(11, action.patchedItemId?.value)
                    val priorPos = action.priorPos
                    if (priorPos != null) {
                        stmt.setString(12, priorPos.name.lowercase())
                    } else {
                        stmt.setNull(12, Types.VARCHAR)
                    }
                    val priorLastRatedAt = action.priorLastRatedAt
                    if (priorLastRatedAt != null) {
                        stmt.setTimestamp(13, Timestamp.from(priorLastRatedAt))
                    } else {
                        stmt.setNull(13, Types.TIMESTAMP)
                    }
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findByTokenHash(tokenHash: ByteArray): SurveyAction? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(FIND_BY_HASH_SQL).use { stmt ->
                    stmt.setBytes(1, tokenHash)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSurveyAction() else null }
                }
            }
        }

    override suspend fun markUndone(
        id: ActionId,
        at: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(MARK_UNDONE_SQL).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(at))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun scrubUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(SCRUB_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    private fun PreparedStatement.setObjectOrNull(
        idx: Int,
        value: UUID?,
    ) {
        if (value != null) setObject(idx, value) else setNull(idx, Types.OTHER)
    }

    private fun ResultSet.toSurveyAction(): SurveyAction {
        val ratingArray = getArray("created_rating_ids")
        val ratingIds = (ratingArray.array as Array<*>).map { RatingId(it as UUID) }
        return SurveyAction(
            id = ActionId(getObject("action_id", UUID::class.java)),
            undoTokenHash = getBytes("undo_token_hash"),
            userId = getObject("user_id", UUID::class.java)?.let { UserId(it) },
            kind = ActionKind.valueOf(getString("kind").uppercase()),
            campaignId = CampaignId(getObject("campaign_id", UUID::class.java)),
            createdAt = getTimestamp("created_at").toInstant(),
            undoneAt = getTimestamp("undone_at")?.toInstant(),
            createdRatingIds = ratingIds,
            createdPairId = getObject("created_pair_id", UUID::class.java)?.let { PairRatingId(it) },
            createdItemId = getObject("created_item_id", UUID::class.java)?.let { ItemId(it) },
            proposedItemId = getObject("proposed_item_id", UUID::class.java)?.let { ItemId(it) },
            patchedItemId = getObject("patched_item_id", UUID::class.java)?.let { ItemId(it) },
            priorPos = getString("prior_pos")?.let { Pos.valueOf(it.uppercase()) },
            priorLastRatedAt = getTimestamp("prior_last_rated_at")?.toInstant(),
        )
    }

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO survey_actions
              (action_id, undo_token_hash, user_id, kind, campaign_id, created_at,
               created_rating_ids, created_pair_id, created_item_id, proposed_item_id,
               patched_item_id, prior_pos, prior_last_rated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """

        const val FIND_BY_HASH_SQL = "SELECT * FROM survey_actions WHERE undo_token_hash = ?"
        const val MARK_UNDONE_SQL = "UPDATE survey_actions SET undone_at = ? WHERE action_id = ?"
        const val SCRUB_USER_SQL = "UPDATE survey_actions SET user_id = NULL WHERE user_id = ?"
    }
}
