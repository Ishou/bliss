package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UpsertSubTagsUseCaseTest {
    private val now = Instant.parse("2026-06-01T08:00:00Z")
    private val clock = Clock { now }
    private val maintainer = UserId(UUID.fromString("11111111-1111-7111-8111-111111111111"))
    private val player = UserId(UUID.fromString("22222222-2222-7222-8222-222222222222"))

    @Test
    fun `non-maintainer is forbidden`() =
        runTest {
            val roles = InMemoryMaintainerRoleRepository()
            roles.rows[player] = MaintainerRole(player, "player", now)
            val uc = UpsertSubTagsUseCase(InMemoryWordMetaRepository(), roles, clock, passThroughTransactionManager)
            assertThat(uc.execute("chat", listOf("felin"), player)).isEqualTo(UpsertSubTagsResult.Forbidden)
        }

    @Test
    fun `maintainer upserts dedupped sub tags`() =
        runTest {
            val roles = InMemoryMaintainerRoleRepository()
            roles.rows[maintainer] = MaintainerRole(maintainer, "maintainer", now)
            val wordMeta = InMemoryWordMetaRepository()
            val uc = UpsertSubTagsUseCase(wordMeta, roles, clock, passThroughTransactionManager)

            val result = uc.execute("chat", listOf("FÉLIN", "felin", "domestique"), maintainer)

            assertThat(result).isEqualTo(UpsertSubTagsResult.Ok)
            assertThat(wordMeta.rows["chat"]!!.subTags).isEqualTo(listOf("FÉLIN", "domestique"))
        }

    @Test
    fun `upsert preserves existing sense inventory`() =
        runTest {
            val roles = InMemoryMaintainerRoleRepository()
            roles.rows[maintainer] = MaintainerRole(maintainer, "maintainer", now)
            val wordMeta = InMemoryWordMetaRepository()
            wordMeta.save(
                com.bliss.survey.domain.model.WordMeta(
                    mot = "chat",
                    subTags = emptyList(),
                    senseInventory = listOf("animal félin"),
                    updatedAt = now,
                ),
            )
            val uc = UpsertSubTagsUseCase(wordMeta, roles, clock, passThroughTransactionManager)

            uc.execute("chat", listOf("felin"), maintainer)

            assertThat(wordMeta.rows["chat"]!!.senseInventory).isEqualTo(listOf("animal félin"))
            assertThat(wordMeta.rows["chat"]!!.subTags).isEqualTo(listOf("felin"))
        }
}
