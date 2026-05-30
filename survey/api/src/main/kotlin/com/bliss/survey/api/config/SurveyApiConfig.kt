package com.bliss.survey.api.config

// Runtime config from env (ADR-0007 §6); missing required values fail-fast at boot.
data class SurveyApiConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val identityBaseUrl: String,
    val allowedOrigins: List<String>,
    val natsUrl: String,
    val goldCutoff: java.time.Instant,
    val goldMultiplier: Double,
) {
    companion object {
        fun load(env: (String) -> String? = System::getenv): SurveyApiConfig =
            SurveyApiConfig(
                port = env("SURVEY_PORT")?.toIntOrNull() ?: 7780,
                jdbcUrl = required(env, "SURVEY_JDBC_URL"),
                dbUser = required(env, "SURVEY_DB_USER"),
                dbPassword = required(env, "SURVEY_DB_PASSWORD"),
                identityBaseUrl = required(env, "IDENTITY_BASE_URL"),
                allowedOrigins =
                    env("SURVEY_ALLOWED_ORIGINS")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: listOf("https://wordsparrow.io", "https://www.wordsparrow.io"),
                // ADR-0049 — JetStream user.deleted consumer URL; default matches in-cluster Service.
                natsUrl = env("NATS_URL") ?: "nats://nats.wordsparrow.svc.cluster.local:4222",
                goldCutoff = env("SURVEY_GOLD_CUTOFF")?.let(java.time.Instant::parse) ?: java.time.Instant.parse("2026-05-30T00:00:00Z"),
                goldMultiplier = env("SURVEY_GOLD_MULTIPLIER")?.toDoubleOrNull() ?: 3.0,
            )

        private fun required(
            env: (String) -> String?,
            key: String,
        ): String = env(key) ?: error("missing required env var: $key")
    }
}
