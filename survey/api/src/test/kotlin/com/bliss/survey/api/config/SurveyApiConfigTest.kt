package com.bliss.survey.api.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class SurveyApiConfigTest {
    private val baseEnv =
        mapOf(
            "SURVEY_JDBC_URL" to "jdbc:postgresql://localhost/survey",
            "SURVEY_DB_USER" to "survey",
            "SURVEY_DB_PASSWORD" to "secret",
            "IDENTITY_BASE_URL" to "http://identity-api:8082",
        )

    @Test
    fun `natsUrl defaults to in-cluster nats service when NATS_URL is absent`() {
        val cfg = SurveyApiConfig.load(baseEnv::get)
        assertThat(cfg.natsUrl).isEqualTo("nats://nats.wordsparrow.svc.cluster.local:4222")
    }

    @Test
    fun `natsUrl reads NATS_URL when present`() {
        val env = baseEnv + ("NATS_URL" to "nats://bliss-nats.wordsparrow:4222")
        val cfg = SurveyApiConfig.load(env::get)
        assertThat(cfg.natsUrl).isEqualTo("nats://bliss-nats.wordsparrow:4222")
    }
}
