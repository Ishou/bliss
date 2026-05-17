package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityDatabaseTest {
    @Test
    fun `migrations apply cleanly and all four identity tables exist`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        val pg =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        try {
            // Pass the Testcontainers JDBC URL directly via JVM property.
            System.setProperty("IDENTITY_DATABASE_URL", pg.jdbcUrl)
            val db =
                IdentityDatabase(
                    poolName = "identity-test",
                    maxPoolSize = 2,
                    requireUrl = true,
                )
            try {
                db.start()
                val ds = db.dataSource()!!
                val tables =
                    ds.connection.use { conn ->
                        conn.metaData
                            .getTables(null, "public", "identity_%", arrayOf("TABLE"))
                            .use { rs ->
                                generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }
                                    .toSortedSet()
                            }
                    }
                assertThat(tables.size).isEqualTo(4)
                assertThat(tables.contains("identity_users")).isEqualTo(true)
                assertThat(tables.contains("identity_user_providers")).isEqualTo(true)
                assertThat(tables.contains("identity_sessions")).isEqualTo(true)
                assertThat(tables.contains("identity_auth_attempts")).isEqualTo(true)
            } finally {
                db.stop()
                System.clearProperty("IDENTITY_DATABASE_URL")
            }
        } finally {
            pg.stop()
        }
    }
}
