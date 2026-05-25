package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.csv.StyleGuideCsvParser
import com.bliss.survey.application.csv.StyleGuideCsvWriter
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class IngestBatchUseCaseTest {
    private val now = Instant.parse("2026-05-25T12:00:00Z")
    private val clock = Clock { now }
    private val ids =
        object : IdGenerator {
            private var n = 0L

            override fun next(): UUID = UUID(1L, n++)
        }
    private val parser = StyleGuideCsvParser()
    private val writer = StyleGuideCsvWriter()
    private val pipeline = FilterPipeline.default { _ -> false }

    private fun row(definition: String): String {
        val item =
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = "POMME",
                definition = definition,
                pos = Pos.NOM_COMMUN,
                categorie = Categorie.ALIMENTS,
                style = Style.DEFINITION_DIRECTE,
                forceClaimed = 3,
                longueur = 5,
                source = Source.CURATED_V1,
                sourceBatch = "input",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = now,
            )
        return writer.toRow(item, emptyMap())
    }

    @Test
    fun `clean rows are accepted and stamped with sourceBatch and tier`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val uc = IngestBatchUseCase(parser, pipeline, repo, ids, clock)
            val lines = listOf(writer.header(), row("Fruit du pommier rouge ou vert au verger"))
            val report = uc.execute(lines, sourceBatch = "ingest1", tier = Tier.HIGH)
            assertThat(report.accepted).isEqualTo(1)
            assertThat(report.rejected.size).isEqualTo(0)
            val stored = repo.items.values.first()
            assertThat(stored.sourceBatch).isEqualTo("ingest1")
            assertThat(stored.tier).isEqualTo(Tier.HIGH)
        }

    @Test
    fun `rejected rows are reported with filter id and reason`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val uc = IngestBatchUseCase(parser, pipeline, repo, ids, clock)
            val lines = listOf(writer.header(), row("Quelqu'un qui mange un fruit"))
            val report = uc.execute(lines, sourceBatch = "ingest2", tier = Tier.MID)
            assertThat(report.accepted).isEqualTo(0)
            assertThat(report.rejected.size).isEqualTo(1)
        }
}
