package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.UserProgress
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource

/** Postgres-backed [UserProgressRepository] for per-user rating cursor + calibration agreement. */
class PgUserProgressRepository(
    private val dataSource: DataSource,
) : UserProgressRepository {
    override suspend fun incrementItemsRated(
        userId: UserId,
        at: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPSERT_INCREMENT_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.setTimestamp(2, Timestamp.from(at))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun updateCalibrationAgreement(
        userId: UserId,
        agreement: Double,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPSERT_AGREEMENT_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.setBigDecimal(2, BigDecimal.valueOf(agreement))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun get(userId: UserId): UserProgress? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        val agreement: Double? = rs.getBigDecimal("calibration_agreement")?.toDouble()
                        val lastRatedAt: Instant? = rs.getTimestamp("last_rated_at")?.toInstant()
                        UserProgress(
                            userId = userId,
                            itemsRated = rs.getInt("items_rated"),
                            calibrationAgreement = agreement,
                            lastRatedAt = lastRatedAt,
                        )
                    }
                }
            }
        }

    override suspend fun deleteByUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    @Suppress("unused")
    private fun coerceNullable(
        stmt: java.sql.PreparedStatement,
        idx: Int,
    ) = stmt.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE)

    private companion object {
        const val UPSERT_INCREMENT_SQL =
            """
            INSERT INTO user_progress (user_id, items_rated, last_rated_at)
            VALUES (?, 1, ?)
            ON CONFLICT (user_id) DO UPDATE
              SET items_rated = user_progress.items_rated + 1,
                  last_rated_at = EXCLUDED.last_rated_at
            """

        const val UPSERT_AGREEMENT_SQL =
            """
            INSERT INTO user_progress (user_id, items_rated, calibration_agreement)
            VALUES (?, 0, ?)
            ON CONFLICT (user_id) DO UPDATE
              SET calibration_agreement = EXCLUDED.calibration_agreement
            """

        const val SELECT_SQL =
            "SELECT items_rated, calibration_agreement, last_rated_at FROM user_progress WHERE user_id = ?"

        const val DELETE_SQL = "DELETE FROM user_progress WHERE user_id = ?"
    }
}
