package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.WordMetaRepository
import com.bliss.survey.domain.model.WordMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PgWordMetaRepository(
    private val dataSource: DataSource,
) : WordMetaRepository {
    override suspend fun find(mot: String): WordMeta? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(FIND_SQL).use { stmt ->
                    stmt.setString(1, mot)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            WordMeta(
                                mot = rs.getString("mot"),
                                subTags = decodeList(rs.getString("sub_tags")),
                                senseInventory = decodeList(rs.getString("sense_inventory")),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    override suspend fun findForUpdate(mot: String): WordMeta? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(ENSURE_ROW_SQL).use { stmt ->
                    stmt.setString(1, mot)
                    stmt.setTimestamp(2, Timestamp.from(Instant.now()))
                    stmt.executeUpdate()
                }
                conn.prepareStatement(FIND_FOR_UPDATE_SQL).use { stmt ->
                    stmt.setString(1, mot)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            WordMeta(
                                mot = rs.getString("mot"),
                                subTags = decodeList(rs.getString("sub_tags")),
                                senseInventory = decodeList(rs.getString("sense_inventory")),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    override suspend fun save(meta: WordMeta): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    stmt.setString(1, meta.mot)
                    stmt.setString(2, encodeList(meta.subTags))
                    stmt.setString(3, encodeList(meta.senseInventory))
                    stmt.setTimestamp(4, Timestamp.from(meta.updatedAt))
                    stmt.executeUpdate()
                }
            }
        }

    private companion object {
        private val LIST_SERIALIZER = ListSerializer(String.serializer())
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun encodeList(items: List<String>): String = JSON.encodeToString(LIST_SERIALIZER, items)

        private fun decodeList(json: String): List<String> = JSON.decodeFromString(LIST_SERIALIZER, json)

        const val FIND_SQL =
            "SELECT mot, sub_tags, sense_inventory, updated_at FROM survey_word_meta WHERE mot = ?"

        const val FIND_FOR_UPDATE_SQL =
            "SELECT mot, sub_tags, sense_inventory, updated_at FROM survey_word_meta WHERE mot = ? FOR UPDATE"

        const val ENSURE_ROW_SQL =
            """
            INSERT INTO survey_word_meta (mot, sub_tags, sense_inventory, updated_at)
            VALUES (?, '[]'::jsonb, '[]'::jsonb, ?)
            ON CONFLICT (mot) DO NOTHING
            """

        const val UPSERT_SQL =
            """
            INSERT INTO survey_word_meta (mot, sub_tags, sense_inventory, updated_at)
            VALUES (?, ?::jsonb, ?::jsonb, ?)
            ON CONFLICT (mot) DO UPDATE
              SET sub_tags = excluded.sub_tags,
                  sense_inventory = excluded.sense_inventory,
                  updated_at = excluded.updated_at
            """
    }
}
