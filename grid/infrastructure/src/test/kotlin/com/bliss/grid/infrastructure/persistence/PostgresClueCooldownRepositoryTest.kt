package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresClueCooldownRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 8
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        if (!::dataSource.isInitialized) return
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("TRUNCATE clue_cooldown_session CASCADE") }
        }
    }

    private fun repo(rolledN: Int = 1): PostgresClueCooldownRepository =
        PostgresClueCooldownRepository(dataSource, randomCooldown = { rolledN })

    @Test
    fun `snapshot of unknown bucket is empty at seq 0`() {
        val snap = repo().snapshot(UUID.randomUUID())
        assertThat(snap.currentSeq).isEqualTo(0L)
        assertThat(snap.onCooldown).isEmpty()
    }

    @Test
    fun `recordGeneration bumps counter and writes rows`() {
        val r = repo(rolledN = 3)
        val sid = UUID.randomUUID()
        val c1 = ClueId("EST", "Verbe etre")
        val c2 = ClueId("FE", "Symbole du fer")

        val seq = r.recordGeneration(sid, listOf(c1, c2), rollMaxInclusive = 8)
        assertThat(seq).isEqualTo(1L)

        val snap = r.snapshot(sid)
        assertThat(snap.currentSeq).isEqualTo(1L)
        assertThat(snap.onCooldown).contains(c1)
        assertThat(snap.onCooldown).contains(c2)
    }

    @Test
    fun `snapshot filters rows whose cooldown_until_seq has expired`() {
        val r = repo(rolledN = 1)
        val sid = UUID.randomUUID()
        val tracked = ClueId("EST", "Verbe etre")
        // seq=1, cooldown_until=2 → on cooldown at seq=1.
        r.recordGeneration(sid, listOf(tracked), rollMaxInclusive = 1)
        // seq=2, cooldown_until_seq <= current_seq → fresh.
        r.recordGeneration(sid, listOf(ClueId("dummy", "x")), rollMaxInclusive = 1)
        assertThat(r.snapshot(sid).onCooldown).doesNotContain(tracked)
    }

    @Test
    fun `upsert refreshes cooldown_until_seq for an existing row`() {
        val r = repo(rolledN = 1)
        val sid = UUID.randomUUID()
        val clue = ClueId("EST", "Verbe etre")
        r.recordGeneration(sid, listOf(clue), rollMaxInclusive = 1)
        r.recordGeneration(sid, listOf(ClueId("dummy", "x")), rollMaxInclusive = 1)
        assertThat(r.snapshot(sid).onCooldown).doesNotContain(clue)
        // Re-record at seq=3 → cooldown_until=4 → on cooldown.
        r.recordGeneration(sid, listOf(clue), rollMaxInclusive = 1)
        assertThat(r.snapshot(sid).onCooldown).contains(clue)
    }

    @Test
    fun `deleteBySession removes counter and rows and returns count`() {
        val r = repo(rolledN = 5)
        val sid = UUID.randomUUID()
        r.recordGeneration(sid, listOf(ClueId("EST", "x"), ClueId("FE", "y")), rollMaxInclusive = 8)

        val deleted = r.deleteBySession(sid)
        assertThat(deleted).isEqualTo(2)

        val snap = r.snapshot(sid)
        assertThat(snap.currentSeq).isEqualTo(0L)
        assertThat(snap.onCooldown).isEmpty()
    }

    @Test
    fun `deleteBySession of unknown bucket returns 0`() {
        assertThat(repo().deleteBySession(UUID.randomUUID())).isEqualTo(0)
    }

    @Test
    fun `concurrent recordGeneration on the same bucket bumps the counter monotonically`() {
        val r = repo(rolledN = 1)
        val sid = UUID.randomUUID()
        val n = 16

        val seqs =
            runBlocking {
                coroutineScope {
                    (1..n)
                        .map { i ->
                            async {
                                r.recordGeneration(sid, listOf(ClueId("W$i", "C$i")), rollMaxInclusive = 1)
                            }
                        }.awaitAll()
                }
            }

        // Every concurrent caller observed a unique seq value; the final
        // counter equals exactly `n`. INSERT ... ON CONFLICT DO UPDATE
        // ... RETURNING is single-statement-atomic, so no two callers can
        // claim the same seq.
        assertThat(seqs.toSet().size).isEqualTo(n)
        assertThat(r.snapshot(sid).currentSeq).isEqualTo(n.toLong())
    }

    @Test
    fun `buckets are isolated`() {
        val r = repo(rolledN = 8)
        val sessionA = UUID.randomUUID()
        val sessionB = UUID.randomUUID()
        val daily = ClueCooldownRepository.DAILY_SCOPE_ID
        val clue = ClueId("EST", "Verbe etre")

        r.recordGeneration(sessionA, listOf(clue), rollMaxInclusive = 8)

        assertThat(r.snapshot(sessionA).onCooldown).contains(clue)
        assertThat(r.snapshot(sessionB).onCooldown).doesNotContain(clue)
        assertThat(r.snapshot(daily).onCooldown).doesNotContain(clue)
    }
}
