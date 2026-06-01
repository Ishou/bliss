package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.PriorLemmaMeta
import com.bliss.survey.application.ports.RatingAggregate
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed [RatingRepository]; anonymise SQL implements spec §9.2. */
class PgRatingRepository(
    private val dataSource: DataSource,
) : RatingRepository {
    override suspend fun findAuthRating(
        itemId: ItemId,
        userId: UserId,
    ): Rating? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(FIND_AUTH_RATING_SQL).use { stmt ->
                    stmt.setObject(1, itemId.value)
                    stmt.setObject(2, userId.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toRating() else null }
                }
            }
        }

    override suspend fun insert(rating: Rating): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, rating.id.value)
                    stmt.setObject(2, rating.itemId.value)
                    val userIdValue = rating.userId
                    if (userIdValue != null) {
                        stmt.setObject(3, userIdValue.value)
                    } else {
                        stmt.setNull(3, Types.OTHER)
                    }
                    stmt.setString(4, rating.submittedAs.name.lowercase())
                    stmt.setInt(5, rating.qualite)
                    stmt.setInt(6, rating.difficulte)
                    val flag = rating.flag
                    if (flag != null) stmt.setString(7, flag.name.lowercase()) else stmt.setNull(7, Types.VARCHAR)
                    val proposed = rating.proposedItemId
                    if (proposed != null) {
                        stmt.setObject(8, proposed.value)
                    } else {
                        stmt.setNull(8, Types.OTHER)
                    }
                    val latency = rating.latencyMs
                    if (latency != null) stmt.setInt(9, latency) else stmt.setNull(9, Types.INTEGER)
                    stmt.setNull(10, Types.OTHER)
                    stmt.setTimestamp(11, Timestamp.from(rating.createdAt))
                    val campaign = rating.campaignId
                    if (campaign != null) {
                        stmt.setObject(12, campaign.value)
                    } else {
                        stmt.setNull(12, Types.OTHER)
                    }
                    stmt.setString(13, encodeStrings(rating.targetCategories.map { it.name.lowercase() }))
                    val sense = rating.targetSense
                    if (sense != null) stmt.setString(14, sense) else stmt.setNull(14, Types.VARCHAR)
                    stmt.setBoolean(15, rating.isMultisense)
                    stmt.setString(16, encodeStrings(rating.subTags))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun deleteByIds(ids: List<RatingId>): Unit =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(DELETE_BY_IDS_SQL).use { stmt ->
                    stmt.setArray(1, conn.createArrayOf("uuid", ids.map { it.value }.toTypedArray()))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun countByItem(itemId: ItemId): Int =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(COUNT_BY_ITEM_SQL).use { stmt ->
                    stmt.setObject(1, itemId.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
        }

    override suspend fun anonymiseForUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(ANONYMISE_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun aggregateForExport(
        since: Instant?,
        settledBefore: Instant,
    ): List<RatingAggregate> =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                val sql = if (since != null) AGGREGATE_SINCE_SQL else AGGREGATE_ALL_SQL
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(settledBefore))
                    if (since != null) stmt.setTimestamp(2, Timestamp.from(since))
                    val out = mutableListOf<RatingAggregate>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            out +=
                                RatingAggregate(
                                    itemId = ItemId(rs.getObject("item_id", UUID::class.java)),
                                    qualiteAuthSum = rs.getInt("qualite_auth_sum"),
                                    qualiteAuthN = rs.getInt("qualite_auth_n"),
                                    qualiteAnonSum = rs.getInt("qualite_anon_sum"),
                                    qualiteAnonN = rs.getInt("qualite_anon_n"),
                                    difficulteAuthSum = rs.getInt("difficulte_auth_sum"),
                                    difficulteAuthN = rs.getInt("difficulte_auth_n"),
                                    difficulteAnonSum = rs.getInt("difficulte_anon_sum"),
                                    difficulteAnonN = rs.getInt("difficulte_anon_n"),
                                    flagCount = rs.getInt("flag_count"),
                                    qualiteSquaredAuthSum = rs.getInt("qualite_sq_auth_sum"),
                                    qualiteSquaredAnonSum = rs.getInt("qualite_sq_anon_sum"),
                                )
                        }
                    }
                    out
                }
            }
        }

    override suspend fun priorMetaForMot(mot: String): PriorLemmaMeta =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(PRIOR_META_SQL).use { stmt ->
                    stmt.setString(1, mot)
                    val senses = mutableListOf<String>()
                    val subTags = mutableListOf<String>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            rs.getString("target_sense")?.let { senses += it }
                            rs.getString("sub_tags")?.let { subTags += decodeStrings(it) }
                        }
                    }
                    PriorLemmaMeta(senses = senses, subTags = subTags)
                }
            }
        }

    private fun ResultSet.toRating(): Rating {
        val userIdValue: UUID? = getObject("user_id", UUID::class.java)
        val proposedValue: UUID? = getObject("proposed_item_id", UUID::class.java)
        val campaignValue: UUID? = getObject("campaign_id", UUID::class.java)
        val rawFlag = getString("flag")
        val latency = getInt("latency_ms").let { if (wasNull()) null else it }
        val rawCategories = getString("target_categories")
        val rawSubTags = getString("sub_tags")
        return Rating(
            id = RatingId(getObject("rating_id", UUID::class.java)),
            itemId = ItemId(getObject("item_id", UUID::class.java)),
            userId = userIdValue?.let(::UserId),
            submittedAs = SubmittedAs.valueOf(getString("submitted_as").uppercase()),
            qualite = getInt("qualite"),
            difficulte = getInt("difficulte"),
            flag = rawFlag?.let { FlagReason.valueOf(it.uppercase()) },
            proposedItemId = proposedValue?.let(::ItemId),
            latencyMs = latency,
            createdAt = getTimestamp("created_at").toInstant(),
            campaignId = campaignValue?.let(::CampaignId),
            targetCategories =
                if (rawCategories != null) {
                    decodeStrings(rawCategories).map { Categorie.valueOf(it.uppercase()) }
                } else {
                    emptyList()
                },
            targetSense = getString("target_sense"),
            isMultisense = getBoolean("is_multisense"),
            subTags = if (rawSubTags != null) decodeStrings(rawSubTags) else emptyList(),
        )
    }

    private companion object {
        private val STRINGS_SERIALIZER = ListSerializer(String.serializer())
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun encodeStrings(items: List<String>): String = JSON.encodeToString(STRINGS_SERIALIZER, items)

        private fun decodeStrings(json: String): List<String> = JSON.decodeFromString(STRINGS_SERIALIZER, json)

        const val INSERT_SQL =
            """
            INSERT INTO ratings
              (rating_id, item_id, user_id, submitted_as, qualite, difficulte,
               flag, proposed_item_id, latency_ms, client_meta, created_at, campaign_id,
               target_categories, target_sense, is_multisense, sub_tags)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
            """

        const val PRIOR_META_SQL =
            """
            SELECT r.target_sense, r.sub_tags
              FROM ratings r
              JOIN survey_items s ON s.item_id = r.item_id
             WHERE s.mot = ?
             ORDER BY r.created_at DESC
            """

        const val FIND_AUTH_RATING_SQL =
            "SELECT * FROM ratings WHERE item_id = ? AND user_id = ? AND submitted_as = 'auth'"

        const val DELETE_BY_IDS_SQL = "DELETE FROM ratings WHERE rating_id = ANY(?)"

        const val COUNT_BY_ITEM_SQL = "SELECT count(*) FROM ratings WHERE item_id = ?"

        const val ANONYMISE_SQL =
            """
            UPDATE ratings
               SET user_id = NULL,
                   client_meta = NULL,
                   latency_ms = NULL,
                   created_at = date_trunc('month', created_at)
             WHERE user_id = ?
            """

        const val AGGREGATE_ALL_SQL =
            """
            SELECT item_id,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN qualite END), 0) AS qualite_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN 1 END), 0) AS qualite_auth_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN qualite END), 0) AS qualite_anon_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN 1 END), 0) AS qualite_anon_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN difficulte END), 0) AS difficulte_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN 1 END), 0) AS difficulte_auth_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN difficulte END), 0) AS difficulte_anon_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN 1 END), 0) AS difficulte_anon_n,
                   COALESCE(SUM(CASE WHEN flag IS NOT NULL THEN 1 END), 0) AS flag_count,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN qualite * qualite END), 0) AS qualite_sq_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN qualite * qualite END), 0) AS qualite_sq_anon_sum
              FROM ratings r
              JOIN campaigns c ON c.campaign_id = r.campaign_id
             WHERE c.closed_at IS NOT NULL
               AND c.closed_at < ?
             GROUP BY item_id
            """

        const val AGGREGATE_SINCE_SQL =
            """
            SELECT item_id,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN qualite END), 0) AS qualite_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN 1 END), 0) AS qualite_auth_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN qualite END), 0) AS qualite_anon_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN 1 END), 0) AS qualite_anon_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN difficulte END), 0) AS difficulte_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN 1 END), 0) AS difficulte_auth_n,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN difficulte END), 0) AS difficulte_anon_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN 1 END), 0) AS difficulte_anon_n,
                   COALESCE(SUM(CASE WHEN flag IS NOT NULL THEN 1 END), 0) AS flag_count,
                   COALESCE(SUM(CASE WHEN submitted_as = 'auth' THEN qualite * qualite END), 0) AS qualite_sq_auth_sum,
                   COALESCE(SUM(CASE WHEN submitted_as = 'anon' THEN qualite * qualite END), 0) AS qualite_sq_anon_sum
              FROM ratings r
              JOIN campaigns c ON c.campaign_id = r.campaign_id
             WHERE c.closed_at IS NOT NULL
               AND c.closed_at < ?
               AND created_at >= ?
             GROUP BY item_id
            """
    }
}
