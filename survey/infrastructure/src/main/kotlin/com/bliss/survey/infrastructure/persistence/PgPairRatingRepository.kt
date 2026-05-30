package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.PairRatingRepository
import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.PreferenceVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** Postgres-backed [PairRatingRepository]; ON CONFLICT on the partial unique index returns false. */
class PgPairRatingRepository(
    private val dataSource: DataSource,
) : PairRatingRepository {
    override suspend fun insert(rating: PairRating): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, rating.id.value)
                    stmt.setObject(2, rating.leftItemId.value)
                    stmt.setObject(3, rating.rightItemId.value)
                    val userIdValue = rating.userId
                    if (userIdValue != null) {
                        stmt.setObject(4, userIdValue.value)
                    } else {
                        stmt.setNull(4, Types.OTHER)
                    }
                    stmt.setString(5, rating.verdict.wire())
                    stmt.setInt(6, rating.difficulte)
                    val latency = rating.latencyMs
                    if (latency != null) stmt.setInt(7, latency) else stmt.setNull(7, Types.INTEGER)
                    stmt.setTimestamp(8, Timestamp.from(rating.createdAt))
                    val campaign = rating.campaignId
                    if (campaign != null) {
                        stmt.setObject(9, campaign.value)
                    } else {
                        stmt.setNull(9, Types.OTHER)
                    }
                    try {
                        stmt.executeUpdate() == 1
                    } catch (e: PSQLException) {
                        // 23505 = unique_violation on pair_ratings_user_unique → idempotent caller path.
                        if (e.sqlState == "23505") return@withContext false
                        throw e
                    }
                }
            }
        }

    private fun PreferenceVerdict.wire(): String =
        when (this) {
            PreferenceVerdict.LEFT_WINS -> "left_wins"
            PreferenceVerdict.RIGHT_WINS -> "right_wins"
        }

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO pair_ratings
              (id, left_item_id, right_item_id, user_id, verdict, difficulte, latency_ms, created_at, campaign_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
    }
}
