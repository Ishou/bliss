package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed [SurveyItemRepository]; raw JDBC (no ORM) per identity/grid precedent. */
class PgSurveyItemRepository(
    private val dataSource: DataSource,
) : SurveyItemRepository {
    override suspend fun insert(item: SurveyItem): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, item.id.value)
                    stmt.setString(2, item.mot)
                    stmt.setString(3, item.definition)
                    stmt.setString(4, item.pos.name.lowercase())
                    stmt.setString(5, item.categorie.name.lowercase())
                    stmt.setString(6, item.style.name.lowercase())
                    stmt.setInt(7, item.forceClaimed)
                    stmt.setInt(8, item.longueur)
                    stmt.setString(9, item.source.name.lowercase())
                    stmt.setString(10, item.sourceBatch)
                    stmt.setString(11, item.tier.name.lowercase())
                    stmt.setBoolean(12, item.isCalibration)
                    stmt.setString(13, null)
                    stmt.setTimestamp(14, item.retiredAt?.let(Timestamp::from))
                    stmt.setTimestamp(15, Timestamp.from(item.createdAt))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findById(id: ItemId): SurveyItem? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSurveyItem() else null }
                }
            }
        }

    override suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                for (k in 0..2) {
                    val pickQuery = buildPickQuery(userId != null, exclude.size)
                    conn.prepareStatement(pickQuery).use { stmt ->
                        var idx = 1
                        if (userId != null) stmt.setObject(idx++, userId.value)
                        stmt.setString(idx++, tier.name.lowercase())
                        for (id in exclude) stmt.setObject(idx++, id.value)
                        stmt.setInt(idx, k)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) return@withContext rs.toSurveyItem()
                        }
                    }
                }
                null
            }
        }

    override suspend fun retire(
        id: ItemId,
        at: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(RETIRE_SQL).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(at))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun countUnretiredByTier(): Map<Tier, Int> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val out = Tier.values().associateWith { 0 }.toMutableMap()
                conn.prepareStatement(COUNT_BY_TIER_SQL).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            out[Tier.valueOf(rs.getString(1).uppercase())] = rs.getInt(2)
                        }
                    }
                }
                out.toMap()
            }
        }

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val results = mutableListOf<ItemId>()
                for ((tier, k) in policy.targetK) {
                    conn.prepareStatement(SATURATED_BY_TIER_SQL).use { stmt ->
                        stmt.setString(1, tier.name.lowercase())
                        stmt.setInt(2, k)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) results += ItemId(rs.getObject(1, UUID::class.java))
                        }
                    }
                }
                results
            }
        }

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val out = mutableListOf<ProposedContribution>()
                conn.prepareStatement(PROPOSED_BY_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            out +=
                                ProposedContribution(
                                    item = rs.toSurveyItem(),
                                    optedOut = rs.getBoolean("opted_out"),
                                    kCoverage = rs.getInt("k_cov"),
                                )
                        }
                    }
                }
                out
            }
        }

    override suspend fun deleteByIds(ids: Collection<ItemId>): Unit =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_BY_IDS_SQL).use { stmt ->
                    val arr = conn.createArrayOf("uuid", ids.map { it.value }.toTypedArray())
                    stmt.setArray(1, arr)
                    stmt.executeUpdate()
                }
            }
        }

    private fun ResultSet.toSurveyItem(): SurveyItem =
        SurveyItem(
            id = ItemId(getObject("item_id", UUID::class.java)),
            mot = getString("mot"),
            definition = getString("definition"),
            pos = Pos.valueOf(getString("pos").uppercase()),
            categorie = Categorie.valueOf(getString("categorie").uppercase()),
            style = Style.valueOf(getString("style").uppercase()),
            forceClaimed = getInt("force_claimed"),
            longueur = getInt("longueur"),
            source = Source.valueOf(getString("source").uppercase()),
            sourceBatch = getString("source_batch"),
            tier = Tier.valueOf(getString("tier").uppercase()),
            isCalibration = getBoolean("is_calibration"),
            expected = null,
            retiredAt = getTimestamp("retired_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun buildPickQuery(
        hasUserId: Boolean,
        excludeSize: Int,
    ): String {
        val joinClause = if (hasUserId) "LEFT JOIN ratings r ON r.item_id = si.item_id AND r.user_id = ?" else ""
        val excludeClause =
            if (excludeSize > 0) "AND si.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        val antiJoinClause = if (hasUserId) "AND r.rating_id IS NULL" else ""
        return """
            SELECT si.* FROM survey_items si
              $joinClause
             WHERE si.tier = ? AND si.retired_at IS NULL
               $excludeClause
               $antiJoinClause
               AND (SELECT count(*) FROM ratings r2 WHERE r2.item_id = si.item_id) = ?
             ORDER BY random() LIMIT 1
            """.trimIndent()
    }

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO survey_items
              (item_id, mot, definition, pos, categorie, style, force_claimed, longueur,
               source, source_batch, tier, is_calibration, expected, retired_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """

        const val SELECT_BY_ID_SQL = "SELECT * FROM survey_items WHERE item_id = ?"

        const val RETIRE_SQL =
            "UPDATE survey_items SET retired_at = ? WHERE item_id = ? AND retired_at IS NULL"

        const val COUNT_BY_TIER_SQL =
            "SELECT tier, count(*) FROM survey_items WHERE retired_at IS NULL GROUP BY tier"

        const val SATURATED_BY_TIER_SQL =
            """
            SELECT si.item_id FROM survey_items si
             WHERE si.tier = ? AND si.retired_at IS NULL
               AND (SELECT count(*) FROM ratings r WHERE r.item_id = si.item_id) >= ?
            """

        const val PROPOSED_BY_USER_SQL =
            """
            SELECT si.*, pb.opted_out,
                   (SELECT count(*) FROM ratings r WHERE r.item_id = si.item_id) AS k_cov
              FROM survey_items si
              JOIN proposed_by pb ON pb.proposed_item_id = si.item_id
             WHERE pb.user_id = ?
             ORDER BY si.created_at DESC
            """

        const val DELETE_BY_IDS_SQL = "DELETE FROM survey_items WHERE item_id = ANY (?)"
    }
}
