package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
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
                    bindInsertParams(stmt, item)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun insertIfAbsent(item: SurveyItem): SurveyItem =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_IF_ABSENT_SQL).use { stmt ->
                    bindInsertParams(stmt, item)
                    stmt.executeQuery().use { rs -> if (rs.next()) return@withContext item }
                }
                conn.prepareStatement(SELECT_BY_CONTENT_SQL).use { stmt ->
                    stmt.setString(1, item.mot)
                    stmt.setString(2, item.definition)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.toSurveyItem() else error("conflict on insert but no existing row for ${item.mot}")
                    }
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
                        stmt.setString(idx++, tier.name.lowercase())
                        for (id in exclude) stmt.setObject(idx++, id.value)
                        if (userId != null) stmt.setObject(idx++, userId.value)
                        stmt.setInt(idx, k)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) return@withContext rs.toSurveyItem()
                        }
                    }
                }
                null
            }
        }

    override suspend fun pickPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                if (userId != null) {
                    pickAnchorPair(conn, userId, exclude)?.let { return@withContext it }
                }
                val mot = pickEligibleMot(conn, userId, exclude) ?: return@withContext null
                pickTwoItemsForMot(conn, mot, userId, exclude)
            }
        }

    // Anchor pair: a caller-rated qualite=5 item as left, plus a same-mot sibling the caller has never seen as right.
    private fun pickAnchorPair(
        conn: java.sql.Connection,
        userId: UserId,
        exclude: Set<ItemId>,
    ): ItemPair? {
        val sql = buildAnchorPairQuery(exclude.size)
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            stmt.setObject(idx++, userId.value) // anchor: qualite=5 rating by caller
            for (id in exclude) stmt.setObject(idx++, id.value) // anchor not excluded
            for (id in exclude) stmt.setObject(idx++, id.value) // sibling not excluded
            stmt.setObject(idx++, userId.value) // sibling not in caller's ratings
            stmt.setObject(idx, userId.value) // sibling not in caller's pair_ratings
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val left = rs.toAnchorItem("a_")
                val right = rs.toAnchorItem("s_")
                return ItemPair(mot = left.mot, left = left, right = right)
            }
        }
    }

    private fun buildAnchorPairQuery(excludeSize: Int): String {
        val anchorExclude =
            if (excludeSize > 0) "AND a.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        val siblingExclude =
            if (excludeSize > 0) "AND s.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        return """
            SELECT a.item_id AS a_item_id, a.mot AS a_mot, a.definition AS a_definition, a.pos AS a_pos,
                   a.categorie AS a_categorie, a.style AS a_style, a.force_claimed AS a_force_claimed,
                   a.longueur AS a_longueur, a.source AS a_source, a.source_batch AS a_source_batch,
                   a.tier AS a_tier, a.is_calibration AS a_is_calibration, a.retired_at AS a_retired_at,
                   a.created_at AS a_created_at,
                   s.item_id AS s_item_id, s.mot AS s_mot, s.definition AS s_definition, s.pos AS s_pos,
                   s.categorie AS s_categorie, s.style AS s_style, s.force_claimed AS s_force_claimed,
                   s.longueur AS s_longueur, s.source AS s_source, s.source_batch AS s_source_batch,
                   s.tier AS s_tier, s.is_calibration AS s_is_calibration, s.retired_at AS s_retired_at,
                   s.created_at AS s_created_at
              FROM survey_items a
              JOIN ratings ar ON ar.item_id = a.item_id AND ar.user_id = ? AND ar.qualite = 5 AND ar.flag IS NULL
              JOIN survey_items s ON s.mot = a.mot AND s.item_id <> a.item_id AND s.retired_at IS NULL
             WHERE a.retired_at IS NULL
               $anchorExclude
               $siblingExclude
               AND NOT EXISTS (
                   SELECT 1 FROM ratings r WHERE r.item_id = s.item_id AND r.user_id = ?
               )
               AND NOT EXISTS (
                   SELECT 1 FROM pair_ratings pr
                    WHERE pr.user_id = ?
                      AND ((pr.left_item_id = a.item_id AND pr.right_item_id = s.item_id)
                        OR (pr.left_item_id = s.item_id AND pr.right_item_id = a.item_id))
               )
             ORDER BY random() LIMIT 1
            """.trimIndent()
    }

    private fun ResultSet.toAnchorItem(prefix: String): SurveyItem =
        SurveyItem(
            id = ItemId(getObject("${prefix}item_id", UUID::class.java)),
            mot = getString("${prefix}mot"),
            definition = getString("${prefix}definition"),
            pos = Pos.valueOf(getString("${prefix}pos").uppercase()),
            categorie = Categorie.valueOf(getString("${prefix}categorie").uppercase()),
            style = Style.valueOf(getString("${prefix}style").uppercase()),
            forceClaimed = getInt("${prefix}force_claimed"),
            longueur = getInt("${prefix}longueur"),
            source = Source.valueOf(getString("${prefix}source").uppercase()),
            sourceBatch = getString("${prefix}source_batch"),
            tier = Tier.valueOf(getString("${prefix}tier").uppercase()),
            isCalibration = getBoolean("${prefix}is_calibration"),
            expected = null,
            retiredAt = getTimestamp("${prefix}retired_at")?.toInstant(),
            createdAt = getTimestamp("${prefix}created_at").toInstant(),
        )

    private fun pickEligibleMot(
        conn: java.sql.Connection,
        userId: UserId?,
        exclude: Set<ItemId>,
    ): String? {
        val sql = buildEligibleMotQuery(userId != null, exclude.size)
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            for (id in exclude) stmt.setObject(idx++, id.value)
            if (userId != null) {
                stmt.setObject(idx++, userId.value)
                stmt.setObject(idx, userId.value)
            }
            stmt.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    private fun pickTwoItemsForMot(
        conn: java.sql.Connection,
        mot: String,
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? {
        val sql = buildTwoItemsForMotQuery(userId != null, exclude.size)
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            stmt.setString(idx++, mot)
            for (id in exclude) stmt.setObject(idx++, id.value)
            if (userId != null) {
                stmt.setObject(idx++, userId.value)
                stmt.setObject(idx, userId.value)
            }
            stmt.executeQuery().use { rs ->
                val picks = mutableListOf<SurveyItem>()
                while (rs.next() && picks.size < 2) picks += rs.toSurveyItem()
                if (picks.size < 2) return null
                return ItemPair(mot = mot, left = picks[0], right = picks[1])
            }
        }
    }

    private fun buildEligibleMotQuery(
        hasUserId: Boolean,
        excludeSize: Int,
    ): String {
        val excludeClause =
            if (excludeSize > 0) "AND si.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        val antiRated =
            if (hasUserId) {
                """
                AND NOT EXISTS (
                    SELECT 1 FROM ratings r WHERE r.item_id = si.item_id AND r.user_id = ?
                )
                AND NOT EXISTS (
                    SELECT 1 FROM pair_ratings pr
                     WHERE (pr.left_item_id = si.item_id OR pr.right_item_id = si.item_id)
                       AND pr.user_id = ?
                )
                """.trimIndent()
            } else {
                ""
            }
        return """
            SELECT si.mot FROM survey_items si
             WHERE si.retired_at IS NULL
               $excludeClause
               $antiRated
             GROUP BY si.mot
            HAVING count(*) >= 2
             ORDER BY random() LIMIT 1
            """.trimIndent()
    }

    private fun buildTwoItemsForMotQuery(
        hasUserId: Boolean,
        excludeSize: Int,
    ): String {
        val excludeClause =
            if (excludeSize > 0) "AND si.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        val antiRated =
            if (hasUserId) {
                """
                AND NOT EXISTS (
                    SELECT 1 FROM ratings r WHERE r.item_id = si.item_id AND r.user_id = ?
                )
                AND NOT EXISTS (
                    SELECT 1 FROM pair_ratings pr
                     WHERE (pr.left_item_id = si.item_id OR pr.right_item_id = si.item_id)
                       AND pr.user_id = ?
                )
                """.trimIndent()
            } else {
                ""
            }
        return """
            SELECT si.* FROM survey_items si
             WHERE si.retired_at IS NULL AND si.mot = ?
               $excludeClause
               $antiRated
             ORDER BY random() LIMIT 2
            """.trimIndent()
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

    override suspend fun updatePos(
        id: ItemId,
        pos: Pos,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_POS_SQL).use { stmt ->
                    stmt.setString(1, pos.name.lowercase())
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

    private fun bindInsertParams(
        stmt: java.sql.PreparedStatement,
        item: SurveyItem,
    ) {
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
        val excludeClause =
            if (excludeSize > 0) "AND si.item_id NOT IN (${List(excludeSize) { "?" }.joinToString(",")})" else ""
        // Dedup by (mot, definition) not item_id: a regenerated identical clue under a new id is still the same logical rating to the user.
        val antiExistsClause =
            if (hasUserId) {
                """
                AND NOT EXISTS (
                    SELECT 1 FROM ratings r
                      JOIN survey_items si2 ON si2.item_id = r.item_id
                     WHERE r.user_id = ?
                       AND si2.mot = si.mot
                       AND si2.definition = si.definition
                )
                """.trimIndent()
            } else {
                ""
            }
        return """
            SELECT si.* FROM survey_items si
             WHERE si.tier = ? AND si.retired_at IS NULL
               $excludeClause
               $antiExistsClause
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

        // Mirrors scripts/clue_generation/import_candidates.py: re-ingesting an existing (mot, definition) no-ops.
        const val INSERT_IF_ABSENT_SQL =
            """
            INSERT INTO survey_items
              (item_id, mot, definition, pos, categorie, style, force_claimed, longueur,
               source, source_batch, tier, is_calibration, expected, retired_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (mot, definition) WHERE retired_at IS NULL DO NOTHING
            RETURNING item_id
            """

        const val SELECT_BY_ID_SQL = "SELECT * FROM survey_items WHERE item_id = ?"

        const val SELECT_BY_CONTENT_SQL =
            "SELECT * FROM survey_items WHERE mot = ? AND definition = ? AND retired_at IS NULL"

        const val RETIRE_SQL =
            "UPDATE survey_items SET retired_at = ? WHERE item_id = ? AND retired_at IS NULL"

        const val UPDATE_POS_SQL =
            "UPDATE survey_items SET pos = ? WHERE item_id = ? AND retired_at IS NULL"

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
