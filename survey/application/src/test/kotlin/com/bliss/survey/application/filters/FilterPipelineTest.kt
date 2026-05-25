package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.survey.domain.model.Pos
import org.junit.jupiter.api.Test

class FilterPipelineTest {
    private val pipeline = FilterPipeline.default()

    @Test
    fun `clean definition accepts`() {
        val r = pipeline.run(FilterInput("POULE", "Femelle du coq de basse cour", Pos.NOM_COMMUN))
        assertThat(r).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `first rejecting filter short-circuits`() {
        // 'Quelqu un qui...' matches Filter4 stereotype before Filter5 auto-reference fires
        val r = pipeline.run(FilterInput("POULE", "Quelqu'un qui pond", Pos.NOM_COMMUN))
        assertThat(r).isInstanceOf(FilterResult.Reject::class)
        check(r is FilterResult.Reject)
        assertThat(r.filterId).isEqualTo(4)
    }
}
