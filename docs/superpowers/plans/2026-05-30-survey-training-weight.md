# Survey `training_weight` from `UserRoleChanged` Implementation Plan (Spec B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `survey` context consumes `UserRoleChanged`, caches the maintainer role durably, and stamps a frozen `training_weight` on maintainer-authored survey items created on/after the 2026-05-30 cutoff.

**Architecture:** Hexagonal, mirroring the existing `UserDeletedConsumer` slice. A pure `GoldWindowPolicy` (domain) defines "what counts as gold." A single idempotent `RecomputeTrainingWeightUseCase` (application) is driven by three triggers: the role event, new rater proposals by a cached maintainer, and a manual worker subcommand. Cached role state lives in a new `maintainer_roles` table; the weight is a stored column on `survey_items`.

**Tech Stack:** Kotlin 2.3.21 + Ktor on JDK 21; Postgres (CNPG) + Flyway; NATS JetStream (jnats 2.20.6); JUnit 5 + assertk + Testcontainers; Helm.

**Source-of-truth spec:** `docs/superpowers/specs/2026-05-30-survey-training-weight-design.md`.

**ADR pre-read (run before coding):** `scripts/adr-context.sh survey/infrastructure/src/main/resources/db/migration/V8__training_weight.sql survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumer.kt survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt survey/api/deploy/chart/values.yaml`. ADR-0049 (NATS consumer lifecycle), ADR-0001 §1 (no cross-context `$ref`), ADR-0056 (survey context) are the load-bearing ones.

**Comment style:** Default to no comment. A comment is one line on a non-obvious *why* only. No multi-line `//` blocks in new code (CLAUDE.md).

---

## Reference signatures (already in the tree — do not re-derive)

- `SurveyItem` (`survey/domain/.../model/SurveyItem.kt`) carries `val createdAt: Instant` and `val id: ItemId`. **No `training_weight` field is added to the domain model** — the weight lives only in the DB column + the writer.
- `UserId`, `ItemId` are `@JvmInline value class … (val value: UUID)` (`survey/domain/.../model/Ids.kt`).
- `SurveyItemRepository.listProposedByUser(userId): List<ProposedContribution>` returns `ProposedContribution(item: SurveyItem, optedOut: Boolean, kCoverage: Int)` — the read for `forUser`.
- `UserDeletedConsumer` / `UserDeletedConsumerConfig` (`survey/infrastructure/.../nats/`) are the templates to mirror exactly.
- `PgProposedByRepository` / `PgSurveyItemRepository` are the JDBC-style templates (`withContext(Dispatchers.IO) { dataSource.connection.use { … prepareStatement(SQL).use { … } } }`, SQL consts in a `private companion object`, `rs.getObject(n, UUID::class.java)` for UUID reads).
- `SurveyTestcontainer` (`survey/infrastructure/src/test/.../persistence/`) gives `startPostgres()`, `dataSourceFor(pg)` (runs Flyway), `truncateAll(ds)`. `PgProposedByRepositoryTest` is the Pg test template; `UserDeletedConsumerTest` is the NATS test template.
- `InMemoryRepositories.kt` (`survey/application/src/test/.../usecases/`) holds the in-memory fakes used by use-case tests.

---

## Task 1: Migration V8 — `training_weight` column + `maintainer_roles` table

**Files:**
- Create: `survey/infrastructure/src/main/resources/db/migration/V8__training_weight.sql`
- Test: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTrainingWeightMigrationTest.kt`

- [ ] **Step 1: Write the migration**

```sql
-- training value of a corpus item; survives GDPR erasure of the author
-- (a bare numeric weight is not PII). DEFAULT 1.0 is the neutral multiplier,
-- so the column is additive with no backfill.
ALTER TABLE survey_items
    ADD COLUMN training_weight NUMERIC NOT NULL DEFAULT 1.0
        CHECK (training_weight > 0);

-- durable cache of cross-context role state; THIS table is PII
-- (user_id -> role) and is erased on UserDeleted.
CREATE TABLE maintainer_roles (
    user_id    UUID PRIMARY KEY,
    role       TEXT NOT NULL CHECK (role IN ('player', 'maintainer')),
    changed_at TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgTrainingWeightMigrationTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var items: PgSurveyItemRepository

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
        items = PgSurveyItemRepository(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `inserted item defaults to training_weight 1_0`() =
        runTest {
            val id = ItemId(UUID.randomUUID())
            items.insert(
                SurveyItem(
                    id = id,
                    mot = "chat",
                    definition = "Animal domestique",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.ANIMALS,
                    style = Style.PERIPHRASE,
                    forceClaimed = 2,
                    longueur = 4,
                    source = Source.RATER_PROPOSED,
                    sourceBatch = "test",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                ),
            )
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT training_weight FROM survey_items WHERE item_id = ?").use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        assertThat(rs.getBigDecimal(1).toDouble()).isEqualTo(1.0)
                    }
                }
            }
        }

    @Test
    fun `maintainer_roles accepts a maintainer row`() =
        runTest {
            val userId = UUID.randomUUID()
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO maintainer_roles (user_id, role, changed_at) VALUES (?, 'maintainer', now())",
                ).use { stmt ->
                    stmt.setObject(1, userId)
                    assertThat(stmt.executeUpdate()).isEqualTo(1)
                }
            }
        }
}
```

- [ ] **Step 3: Run and verify**

Run: `./gradlew :survey:infrastructure:test --tests '*PgTrainingWeightMigrationTest' --parallel`
Expected: PASS (skips if Docker absent).

- [ ] **Step 4: Commit**

```bash
git add survey/infrastructure/src/main/resources/db/migration/V8__training_weight.sql \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTrainingWeightMigrationTest.kt
git commit -s -m "feat(survey-infrastructure): V8 training_weight column + maintainer_roles cache"
```

---

## Task 2: `GoldWindowPolicy` domain function

**Files:**
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/weight/GoldWindowPolicy.kt`
- Test: `survey/domain/src/test/kotlin/com/bliss/survey/domain/weight/GoldWindowPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.survey.domain.weight

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class GoldWindowPolicyTest {
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")
    private val policy = GoldWindowPolicy(cutoff = cutoff, goldMultiplier = 3.0)

    @Test
    fun `pre-cutoff maintainer item is neutral`() {
        assertThat(policy.weightFor(cutoff.minusSeconds(1), isMaintainer = true)).isEqualTo(1.0)
    }

    @Test
    fun `post-cutoff maintainer item is gold`() {
        assertThat(policy.weightFor(cutoff.plusSeconds(1), isMaintainer = true)).isEqualTo(3.0)
    }

    @Test
    fun `post-cutoff non-maintainer item is neutral`() {
        assertThat(policy.weightFor(cutoff.plusSeconds(1), isMaintainer = false)).isEqualTo(1.0)
    }

    @Test
    fun `item created exactly at the cutoff is gold (inclusive)`() {
        assertThat(policy.weightFor(cutoff, isMaintainer = true)).isEqualTo(3.0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :survey:domain:test --tests '*GoldWindowPolicyTest' --parallel`
Expected: FAIL — `GoldWindowPolicy` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.bliss.survey.domain.weight

import java.time.Instant

// Future gold windows (per-campaign, graded multipliers) extend here without touching call sites.
class GoldWindowPolicy(
    private val cutoff: Instant,
    private val goldMultiplier: Double,
) {
    fun weightFor(
        createdAt: Instant,
        isMaintainer: Boolean,
    ): Double = if (isMaintainer && !createdAt.isBefore(cutoff)) goldMultiplier else 1.0
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :survey:domain:test --tests '*GoldWindowPolicyTest' --parallel`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/domain/src/main/kotlin/com/bliss/survey/domain/weight/GoldWindowPolicy.kt \
        survey/domain/src/test/kotlin/com/bliss/survey/domain/weight/GoldWindowPolicyTest.kt
git commit -s -m "feat(survey-domain): GoldWindowPolicy for training-weight gold window"
```

---

## Task 3: `updateTrainingWeight` on the items repository + `MaintainerRoleRepository`

This task changes the `SurveyItemRepository` interface, so **every implementor must be updated in the same commit** to keep the build green: `PgSurveyItemRepository`, `InMemorySurveyItemRepository`, and the `NoopItems` fake in `UserDeletedConsumerTest`. It also introduces the `MaintainerRoleRepository` port + its Postgres adapter.

**Files:**
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/SurveyItemRepository.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/MaintainerRoleRepository.kt`
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgMaintainerRoleRepository.kt`
- Modify: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryRepositories.kt`
- Modify: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/nats/UserDeletedConsumerTest.kt` (add the override to `NoopItems`)
- Test: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgMaintainerRoleRepositoryTest.kt`

- [ ] **Step 1: Add the interface method**

In `SurveyItemRepository.kt`, add after `deleteByIds`:

```kotlin
    suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    )
```

- [ ] **Step 2: Create the `MaintainerRoleRepository` port**

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface MaintainerRoleRepository {
    suspend fun find(userId: UserId): MaintainerRole?

    suspend fun upsert(role: MaintainerRole)

    suspend fun delete(userId: UserId)

    suspend fun listMaintainers(): List<UserId>
}

data class MaintainerRole(
    val userId: UserId,
    val role: String,
    val changedAt: Instant,
)
```

- [ ] **Step 3: Implement `updateTrainingWeight` on `PgSurveyItemRepository`**

Add the override (next to `updatePos`):

```kotlin
    override suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_TRAINING_WEIGHT_SQL).use { stmt ->
                    stmt.setBigDecimal(1, java.math.BigDecimal.valueOf(weight))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }
```

Add the SQL const to the `private companion object`:

```kotlin
        const val UPDATE_TRAINING_WEIGHT_SQL =
            "UPDATE survey_items SET training_weight = ? WHERE item_id = ?"
```

- [ ] **Step 4: Implement `InMemorySurveyItemRepository.updateTrainingWeight`**

In `InMemoryRepositories.kt`, inside `InMemorySurveyItemRepository`, add a weights map and the override (place near `deleteByIds`):

```kotlin
    val trainingWeights: MutableMap<ItemId, Double> = linkedMapOf()

    override suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    ) {
        trainingWeights[id] = weight
    }
```

- [ ] **Step 5: Update the `NoopItems` fake in `UserDeletedConsumerTest`**

Add the override inside `private object NoopItems : SurveyItemRepository`:

```kotlin
        override suspend fun updateTrainingWeight(
            id: ItemId,
            weight: Double,
        ) = Unit
```

- [ ] **Step 6: Create `PgMaintainerRoleRepository`**

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/** Postgres-backed durable cache of cross-context role state (Spec B). */
class PgMaintainerRoleRepository(
    private val dataSource: DataSource,
) : MaintainerRoleRepository {
    override suspend fun find(userId: UserId): MaintainerRole? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(FIND_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            MaintainerRole(
                                userId = userId,
                                role = rs.getString("role"),
                                changedAt = rs.getTimestamp("changed_at").toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    override suspend fun upsert(role: MaintainerRole): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    stmt.setObject(1, role.userId.value)
                    stmt.setString(2, role.role)
                    stmt.setTimestamp(3, Timestamp.from(role.changedAt))
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun delete(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DELETE_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun listMaintainers(): List<UserId> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(LIST_MAINTAINERS_SQL).use { stmt ->
                    val out = mutableListOf<UserId>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) out += UserId(rs.getObject(1, UUID::class.java))
                    }
                    out
                }
            }
        }

    private companion object {
        const val FIND_SQL = "SELECT role, changed_at FROM maintainer_roles WHERE user_id = ?"
        const val UPSERT_SQL =
            """
            INSERT INTO maintainer_roles (user_id, role, changed_at)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET role = excluded.role, changed_at = excluded.changed_at
            """
        const val DELETE_SQL = "DELETE FROM maintainer_roles WHERE user_id = ?"
        const val LIST_MAINTAINERS_SQL = "SELECT user_id FROM maintainer_roles WHERE role = 'maintainer'"
    }
}
```

- [ ] **Step 7: Write the `PgMaintainerRoleRepository` test**

```kotlin
package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.domain.model.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgMaintainerRoleRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PgMaintainerRoleRepository

    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun freshRepo() {
        if (::dataSource.isInitialized) repo = PgMaintainerRoleRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { conn ->
                conn.prepareStatement("TRUNCATE maintainer_roles").use { it.executeUpdate() }
            }
        }
    }

    @Test
    fun `find returns null when absent`() =
        runTest {
            assertThat(repo.find(UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `upsert then find round-trips role and changedAt`() =
        runTest {
            val role = MaintainerRole(UserId(UUID.randomUUID()), "maintainer", now)
            repo.upsert(role)
            assertThat(repo.find(role.userId)).isEqualTo(role)
        }

    @Test
    fun `upsert overwrites an existing row`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(userId, "maintainer", now))
            repo.upsert(MaintainerRole(userId, "player", now.plusSeconds(60)))
            assertThat(repo.find(userId)).isEqualTo(MaintainerRole(userId, "player", now.plusSeconds(60)))
        }

    @Test
    fun `delete removes the row`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(userId, "maintainer", now))
            repo.delete(userId)
            assertThat(repo.find(userId)).isNull()
        }

    @Test
    fun `listMaintainers returns only maintainer-role users`() =
        runTest {
            val keeper = UserId(UUID.randomUUID())
            val player = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(keeper, "maintainer", now))
            repo.upsert(MaintainerRole(player, "player", now))
            assertThat(repo.listMaintainers()).containsExactlyInAnyOrder(keeper)
        }
}
```

- [ ] **Step 8: Run module builds (interface change ripples to every module)**

Run: `./gradlew :survey:application:test :survey:infrastructure:test --parallel`
Expected: PASS — including `PgMaintainerRoleRepositoryTest` and the unchanged `UserDeletedConsumerTest`.

- [ ] **Step 9: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/ports/SurveyItemRepository.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/ports/MaintainerRoleRepository.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgMaintainerRoleRepository.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryRepositories.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/nats/UserDeletedConsumerTest.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgMaintainerRoleRepositoryTest.kt
git commit -s -m "feat(survey): training-weight writer + maintainer-role cache repository"
```

---

## Task 4: `RecomputeTrainingWeightUseCase`

The one idempotent recompute, driven by all three triggers.

**Files:**
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/RecomputeTrainingWeightUseCase.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryMaintainerRoleRepository.kt`
- Test: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/RecomputeTrainingWeightUseCaseTest.kt`

- [ ] **Step 1: Add the in-memory `MaintainerRoleRepository` fake**

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.domain.model.UserId

class InMemoryMaintainerRoleRepository : MaintainerRoleRepository {
    val rows: MutableMap<UserId, MaintainerRole> = linkedMapOf()

    override suspend fun find(userId: UserId): MaintainerRole? = rows[userId]

    override suspend fun upsert(role: MaintainerRole) {
        rows[role.userId] = role
    }

    override suspend fun delete(userId: UserId) {
        rows.remove(userId)
    }

    override suspend fun listMaintainers(): List<UserId> =
        rows.values.filter { it.role == "maintainer" }.map { it.userId }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.weight.GoldWindowPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RecomputeTrainingWeightUseCaseTest {
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")
    private val policy = GoldWindowPolicy(cutoff, goldMultiplier = 3.0)

    private val items = InMemorySurveyItemRepository()
    private val roles = InMemoryMaintainerRoleRepository()
    private val useCase = RecomputeTrainingWeightUseCase(roles, items, policy)

    private fun item(
        id: ItemId,
        createdAt: Instant,
    ): SurveyItem =
        SurveyItem(
            id = id,
            mot = "chat",
            definition = "def ${id.value}",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = 4,
            source = Source.RATER_PROPOSED,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = createdAt,
        )

    @Test
    fun `role grant back-stamps post-cutoff items, leaves pre-cutoff neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val post = ItemId(UUID.randomUUID())
            val pre = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(
                    author to
                        listOf(
                            ProposedContribution(item(post, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2),
                            ProposedContribution(item(pre, cutoff.minusSeconds(1)), optedOut = false, kCoverage = 2),
                        ),
                )

            useCase.onRoleChanged(author, "maintainer", cutoff)

            assertThat(items.trainingWeights[post]).isEqualTo(3.0)
            assertThat(items.trainingWeights[pre]).isEqualTo(1.0)
        }

    @Test
    fun `role revocation resets the user's items to neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(author to listOf(ProposedContribution(item(itemId, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2)))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.onRoleChanged(author, "player", cutoff.plusSeconds(60))

            assertThat(items.trainingWeights[itemId]).isEqualTo(1.0)
        }

    @Test
    fun `out-of-order event older than the cached one is ignored`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            roles.upsert(MaintainerRole(author, "maintainer", cutoff.plusSeconds(100)))

            useCase.onRoleChanged(author, "player", cutoff.plusSeconds(10))

            assertThat(roles.find(author)).isEqualTo(MaintainerRole(author, "maintainer", cutoff.plusSeconds(100)))
        }

    @Test
    fun `forItem stamps gold when the cached author is a maintainer`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.items[itemId] = item(itemId, cutoff.plusSeconds(1))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.forItem(itemId, author)

            assertThat(items.trainingWeights[itemId]).isEqualTo(3.0)
        }

    @Test
    fun `forItem leaves a non-maintainer author's item neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.items[itemId] = item(itemId, cutoff.plusSeconds(1))

            useCase.forItem(itemId, author)

            assertThat(items.trainingWeights[itemId]).isEqualTo(1.0)
        }

    @Test
    fun `recomputeAll restamps every cached maintainer's items`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(author to listOf(ProposedContribution(item(itemId, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2)))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.recomputeAll()

            assertThat(items.trainingWeights[itemId]).isEqualTo(3.0)
        }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :survey:application:test --tests '*RecomputeTrainingWeightUseCaseTest' --parallel`
Expected: FAIL — `RecomputeTrainingWeightUseCase` unresolved.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.weight.GoldWindowPolicy
import java.time.Instant

class RecomputeTrainingWeightUseCase(
    private val maintainerRoles: MaintainerRoleRepository,
    private val items: SurveyItemRepository,
    private val policy: GoldWindowPolicy,
) {
    suspend fun onRoleChanged(
        userId: UserId,
        role: String,
        changedAt: Instant,
    ) {
        val cached = maintainerRoles.find(userId)
        if (cached != null && changedAt.isBefore(cached.changedAt)) return
        maintainerRoles.upsert(MaintainerRole(userId, role, changedAt))
        forUser(userId, isMaintainer = role == MAINTAINER)
    }

    suspend fun forItem(
        itemId: ItemId,
        authorUserId: UserId,
    ) {
        val item = items.findById(itemId) ?: return
        val isMaintainer = maintainerRoles.find(authorUserId)?.role == MAINTAINER
        items.updateTrainingWeight(itemId, policy.weightFor(item.createdAt, isMaintainer))
    }

    suspend fun recomputeAll() {
        for (userId in maintainerRoles.listMaintainers()) forUser(userId, isMaintainer = true)
    }

    private suspend fun forUser(
        userId: UserId,
        isMaintainer: Boolean,
    ) {
        for (c in items.listProposedByUser(userId)) {
            items.updateTrainingWeight(c.item.id, policy.weightFor(c.item.createdAt, isMaintainer))
        }
    }

    private companion object {
        const val MAINTAINER = "maintainer"
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :survey:application:test --tests '*RecomputeTrainingWeightUseCaseTest' --parallel`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/usecases/RecomputeTrainingWeightUseCase.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/InMemoryMaintainerRoleRepository.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/RecomputeTrainingWeightUseCaseTest.kt
git commit -s -m "feat(survey-application): RecomputeTrainingWeightUseCase with three triggers"
```

---

## Task 5: `UserRoleChangedConsumer` + config

Mirror `UserDeletedConsumer*` exactly. The consumer decodes the event and calls `recompute.onRoleChanged`.

**Files:**
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumerConfig.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumer.kt`
- Test: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumerTest.kt`

- [ ] **Step 1: Write the config**

```kotlin
package com.bliss.survey.infrastructure.nats

import io.nats.client.Connection
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import java.time.Duration

/** Consumer constants shared by bootstrap Job and api bind path (ADR-0049). */
object UserRoleChangedConsumerConfig {
    const val SUBJECT: String = "wordsparrow.user.role-changed"
    const val STREAM_NAME: String = "WORDSPARROW_USER_EVENTS"
    const val DURABLE_NAME: String = "survey-api-user-role-changed"

    // Deterministic so addOrUpdateConsumer is idempotent across helm upgrades.
    const val DELIVER_SUBJECT: String = "_DELIVER.survey-api.user-role-changed"

    fun consumerConfiguration(): ConsumerConfiguration =
        ConsumerConfiguration
            .builder()
            .durable(DURABLE_NAME)
            .filterSubject(SUBJECT)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .maxDeliver(5)
            .deliverSubject(DELIVER_SUBJECT)
            .build()

    /** Throws JetStreamApiException (10013) on immutable-field conflict; resolve with --delete-consumer. */
    fun bootstrap(nats: Connection) {
        nats.jetStreamManagement().addOrUpdateConsumer(STREAM_NAME, consumerConfiguration())
    }

    /** Resolves immutable-field migration conflicts; operator-invoked only. */
    fun deleteConsumer(nats: Connection) {
        nats.jetStreamManagement().deleteConsumer(STREAM_NAME, DURABLE_NAME)
    }
}
```

- [ ] **Step 2: Write the consumer**

```kotlin
package com.bliss.survey.infrastructure.nats

import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase
import com.bliss.survey.domain.model.UserId
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamSubscription
import io.nats.client.PushSubscribeOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Binds to the pre-created durable (ADR-0049); lifecycle owned by chart's pre-install Job. */
class UserRoleChangedConsumer(
    private val nats: Connection,
    private val recompute: RecomputeTrainingWeightUseCase,
    private val scope: CoroutineScope,
    private val streamName: String = UserRoleChangedConsumerConfig.STREAM_NAME,
    private val durableName: String = UserRoleChangedConsumerConfig.DURABLE_NAME,
    private val pollWait: Duration = Duration.ofSeconds(1),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Volatile
    private var job: Job? = null

    @Volatile
    private var subscription: JetStreamSubscription? = null

    fun start(): Job? =
        synchronized(this) {
            val existing = job
            if (existing != null && existing.isActive) return existing
            val sub =
                try {
                    nats.jetStream().subscribe(
                        UserRoleChangedConsumerConfig.SUBJECT,
                        PushSubscribeOptions
                            .builder()
                            .bind(true)
                            .stream(streamName)
                            .durable(durableName)
                            .build(),
                    )
                } catch (e: JetStreamApiException) {
                    log.warn(
                        "survey_user_role_changed_consumer_bind_failed stream={} durable={} error={}",
                        streamName,
                        durableName,
                        e.toString(),
                    )
                    return null
                } catch (e: IllegalArgumentException) {
                    log.warn(
                        "survey_user_role_changed_consumer_bind_failed stream={} durable={} error={}",
                        streamName,
                        durableName,
                        e.toString(),
                    )
                    return null
                }
            subscription = sub
            val newJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        val msg =
                            try {
                                sub.nextMessage(pollWait)
                            } catch (e: IllegalStateException) {
                                return@launch
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@launch
                            } ?: continue
                        try {
                            val event = json.decodeFromString(UserRoleChangedPayload.serializer(), msg.data.decodeToString())
                            recompute.onRoleChanged(
                                UserId(UUID.fromString(event.userId)),
                                event.role,
                                Instant.parse(event.changedAt),
                            )
                            msg.ack()
                        } catch (e: Exception) {
                            log.error("survey_user_role_changed_consume_failed subject={} error={}", msg.subject, e.toString(), e)
                            msg.nak()
                        }
                    }
                }
            job = newJob
            newJob
        }

    fun stop() {
        subscription?.let { runCatching { it.unsubscribe() } }
        subscription = null
        job?.cancel()
        job = null
    }

    companion object {
        const val SUBJECT: String = UserRoleChangedConsumerConfig.SUBJECT
        const val STREAM_NAME: String = UserRoleChangedConsumerConfig.STREAM_NAME
        const val DURABLE_NAME: String = UserRoleChangedConsumerConfig.DURABLE_NAME
    }
}

@Serializable
internal data class UserRoleChangedPayload(
    val userId: String,
    val role: String,
    val changedAt: String,
)
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.bliss.survey.infrastructure.nats

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase
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
import com.bliss.survey.domain.weight.GoldWindowPolicy
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRoleChangedConsumerTest {
    private lateinit var natsContainer: GenericContainer<*>
    private lateinit var nats: Connection
    private val scope = CoroutineScope(SupervisorJob())
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")

    @BeforeAll
    fun startNats() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        natsContainer =
            GenericContainer(DockerImageName.parse("nats:2.10-alpine"))
                .withCommand("-js")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
        natsContainer.start()
        val url = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        nats =
            Nats.connect(
                Options
                    .Builder()
                    .server(url)
                    .connectionTimeout(Duration.ofSeconds(5))
                    .build(),
            )
        nats.jetStreamManagement().addStream(
            StreamConfiguration
                .builder()
                .name(UserRoleChangedConsumer.STREAM_NAME)
                .subjects(UserRoleChangedConsumer.SUBJECT)
                .retentionPolicy(RetentionPolicy.Limits)
                .storageType(StorageType.Memory)
                .build(),
        )
    }

    @AfterAll
    fun stopNats() {
        if (::nats.isInitialized) nats.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    @Test
    fun `consumer decodes a role-grant event and stamps the maintainer's post-cutoff item`() =
        runBlocking {
            UserRoleChangedConsumerConfig.bootstrap(nats)

            val author = UserId(UUID.fromString("00000000-0000-7000-8000-000000000010"))
            val itemId = ItemId(UUID.randomUUID())
            val item = sampleItem(itemId, cutoff.plusSeconds(1))
            val items = RecordingItems(mapOf(author to listOf(item)))
            val roles = MapRoles()
            val recompute = RecomputeTrainingWeightUseCase(roles, items, GoldWindowPolicy(cutoff, 3.0))
            val consumer = UserRoleChangedConsumer(nats, recompute, scope, pollWait = Duration.ofMillis(200))
            consumer.start()

            val payload =
                """{"userId":"${author.value}","role":"maintainer","changedAt":"${Instant.parse("2026-05-30T08:00:00Z")}"}"""
            nats.jetStream().publish(UserRoleChangedConsumer.SUBJECT, payload.toByteArray())

            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (items.weights[itemId] == null && System.nanoTime() < deadline) delay(50)
            consumer.stop()

            assertThat(items.weights[itemId]).isEqualTo(3.0)
        }

    private fun sampleItem(
        id: ItemId,
        createdAt: Instant,
    ): SurveyItem =
        SurveyItem(
            id = id,
            mot = "chat",
            definition = "def",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = 4,
            source = Source.RATER_PROPOSED,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = createdAt,
        )

    private class MapRoles : MaintainerRoleRepository {
        val rows = ConcurrentHashMap<UserId, MaintainerRole>()

        override suspend fun find(userId: UserId): MaintainerRole? = rows[userId]

        override suspend fun upsert(role: MaintainerRole) {
            rows[role.userId] = role
        }

        override suspend fun delete(userId: UserId) {
            rows.remove(userId)
        }

        override suspend fun listMaintainers(): List<UserId> = rows.values.filter { it.role == "maintainer" }.map { it.userId }
    }

    private class RecordingItems(
        private val proposed: Map<UserId, List<SurveyItem>>,
    ) : SurveyItemRepository {
        val weights = ConcurrentHashMap<ItemId, Double>()

        override suspend fun updateTrainingWeight(
            id: ItemId,
            weight: Double,
        ) {
            weights[id] = weight
        }

        override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> =
            proposed[userId].orEmpty().map { ProposedContribution(it, optedOut = false, kCoverage = 2) }

        override suspend fun findById(id: ItemId): SurveyItem? = proposed.values.flatten().firstOrNull { it.id == id }

        override suspend fun insert(item: SurveyItem) = Unit

        override suspend fun insertIfAbsent(item: SurveyItem): SurveyItem = item

        override suspend fun retire(
            id: ItemId,
            at: Instant,
        ) = Unit

        override suspend fun updatePos(
            id: ItemId,
            pos: Pos,
        ) = Unit

        override suspend fun pickUnratedForUser(
            userId: UserId?,
            tier: Tier,
            exclude: Set<ItemId>,
        ): SurveyItem? = null

        override suspend fun pickPairForUser(
            userId: UserId?,
            exclude: Set<ItemId>,
        ): ItemPair? = null

        override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

        override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

        override suspend fun deleteByIds(ids: Collection<ItemId>) = Unit
    }
}
```

- [ ] **Step 4: Run to verify it passes (after implementing Steps 1-2)**

Run: `./gradlew :survey:infrastructure:test --tests '*UserRoleChangedConsumerTest' --parallel`
Expected: PASS (skips if Docker absent).

- [ ] **Step 5: Commit**

```bash
git add survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumer.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumerConfig.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumerTest.kt
git commit -s -m "feat(survey-infrastructure): UserRoleChangedConsumer mirroring UserDeleted slice"
```

---

## Task 6: Trigger-2 (new-proposal stamp) + UserDeleted erasure + full wiring

This is the integration task. It changes two use-case constructors, so it **must** update `survey/api/.../Main.kt` (the only production construction site) in the same commit to keep the build green. It also wires the consumer, adds the gold config, the worker subcommand, and the chart plumbing. Because it touches `application` + `api` + `worker` + `chart` as one coherent integration layer, it will exceed the 400-line target — invoke the standing cap-override (ADR-0001 §4 soft-target, 2026-05-25 amendment) in the PR body, citing that splitting would create dependent half-wired PRs.

**Files:**
- Modify: `survey/application/.../usecases/AnonymizeUserRatingsUseCase.kt` (+ test)
- Modify: `survey/application/.../usecases/SubmitRatingUseCase.kt` (+ test)
- Modify: `survey/api/.../config/SurveyApiConfig.kt`
- Modify: `survey/api/.../Main.kt`, `survey/api/.../Wiring.kt`, `survey/api/.../Module.kt`
- Modify: `survey/worker/.../Main.kt`
- Modify: `survey/api/deploy/chart/values.yaml`; Create: `survey/api/deploy/chart/templates/job-recompute-weights.yaml`
- Modify tests: `AnonymizeUserRatingsUseCaseTest`, `SubmitRatingUseCaseTest`, and any direct constructor callers under `survey/api/src/test` / `survey/application/src/test`.

### 6a — Extend `AnonymizeUserRatingsUseCase` to erase the cached role

- [ ] **Step 1: Write the failing test** (add to `AnonymizeUserRatingsUseCaseTest`)

```kotlin
    @Test
    fun `erases the cached maintainer role on user deletion`() =
        runTest {
            val roles = InMemoryMaintainerRoleRepository()
            val userId = UserId(UUID.randomUUID())
            roles.upsert(MaintainerRole(userId, "maintainer", Instant.parse("2026-05-30T00:00:00Z")))
            val useCase =
                AnonymizeUserRatingsUseCase(
                    ratings = InMemoryRatingRepository(),
                    proposedBy = InMemoryProposedByRepository(),
                    items = InMemorySurveyItemRepository(),
                    progress = InMemoryUserProgressRepository(),
                    maintainerRoles = roles,
                )
            useCase.execute(userId)
            assertThat(roles.find(userId)).isNull()
        }
```

(Add imports `com.bliss.survey.application.ports.MaintainerRole`, `assertk.assertions.isNull` as needed. Fix the existing tests' `AnonymizeUserRatingsUseCase(...)` constructions to pass `maintainerRoles = InMemoryMaintainerRoleRepository()`.)

- [ ] **Step 2: Update the use case**

```kotlin
class AnonymizeUserRatingsUseCase(
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val items: SurveyItemRepository,
    private val progress: UserProgressRepository,
    private val maintainerRoles: MaintainerRoleRepository,
) {
    suspend fun execute(userId: UserId) {
        val optedOut = proposedBy.listOptedOutByUser(userId)
        if (optedOut.isNotEmpty()) items.deleteByIds(optedOut)
        ratings.anonymiseForUser(userId)
        proposedBy.deleteByUser(userId)
        progress.deleteByUser(userId)
        maintainerRoles.delete(userId)
    }
}
```

(Add `import com.bliss.survey.application.ports.MaintainerRoleRepository`.)

- [ ] **Step 3: Fix the `UserDeletedConsumerTest` construction site** — pass a `NoopMaintainerRoles` object (add it to that test) to `AnonymizeUserRatingsUseCase(...)`.

### 6b — Extend `SubmitRatingUseCase` with trigger-2

- [ ] **Step 4: Write the failing test** (add to `SubmitRatingUseCaseTest`)

Construct the use case with a real `RecomputeTrainingWeightUseCase` over in-memory repos, pre-cache the rater as a maintainer, submit a correctif whose `createdAt` (the clock `now`) is post-cutoff, and assert the new item's weight is gold.

```kotlin
    @Test
    fun `correctif by a cached maintainer is stamped gold`() =
        runTest {
            val rater = UserId(UUID.randomUUID())
            val roles = InMemoryMaintainerRoleRepository()
            roles.upsert(MaintainerRole(rater, "maintainer", Instant.parse("2026-05-30T00:00:00Z")))
            val recompute = RecomputeTrainingWeightUseCase(roles, items, GoldWindowPolicy(Instant.parse("2026-05-30T00:00:00Z"), 3.0))
            // ... build SubmitRatingUseCase with recompute and a clock at 2026-05-30T09:00:00Z ...
            // submit a correctif that changes the definition, then:
            val proposed = items.trainingWeights.entries.single()
            assertThat(proposed.value).isEqualTo(3.0)
        }
```

(Reuse the test's existing fixture builders for `SubmitRatingUseCase`; the new wiring is the `recompute` constructor argument and a maintainer pre-cached in `roles`. Ensure the campaign is open so the path is not `Locked`.)

- [ ] **Step 5: Update the use case** — add the dependency and the call:

```kotlin
class SubmitRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val filters: FilterPipeline,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val campaigns: CampaignRepository,
    private val recompute: RecomputeTrainingWeightUseCase,
) {
```

After the existing `proposedBy.insert(stored.id, nonNullUserId, optedOut = false)` line:

```kotlin
                proposedBy.insert(stored.id, nonNullUserId, optedOut = false)
                recompute.forItem(stored.id, nonNullUserId)
```

(Add `import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase` — same package, so no import needed.)

### 6c — Gold config

- [ ] **Step 6: Extend `SurveyApiConfig`**

Add fields and loaders:

```kotlin
    val goldCutoff: java.time.Instant,
    val goldMultiplier: Double,
```

In `load(...)`:

```kotlin
                goldCutoff = env("SURVEY_GOLD_CUTOFF")?.let(java.time.Instant::parse) ?: java.time.Instant.parse("2026-05-30T00:00:00Z"),
                goldMultiplier = env("SURVEY_GOLD_MULTIPLIER")?.toDoubleOrNull() ?: 3.0,
```

### 6d — api `Main.kt` wiring

- [ ] **Step 7: Wire the policy, repo, use case, and consumer**

After `val proposedBy = PgProposedByRepository(dataSource)` add:

```kotlin
    val maintainerRoles = PgMaintainerRoleRepository(dataSource)
    val goldPolicy = GoldWindowPolicy(config.goldCutoff, config.goldMultiplier)
    val recompute = RecomputeTrainingWeightUseCase(maintainerRoles, items, goldPolicy)
```

Pass `recompute = recompute` to the `SubmitRatingUseCase(...)` constructor (Task 6b).

Change the anonymise construction to:

```kotlin
    val anonymise = AnonymizeUserRatingsUseCase(ratings, proposedBy, items, progress, maintainerRoles)
```

After `userDeletedConsumer.start()` add:

```kotlin
    val userRoleChangedConsumer = UserRoleChangedConsumer(natsConn, recompute, consumerScope)
    userRoleChangedConsumer.start()
```

Add `userRoleChangedConsumer = userRoleChangedConsumer` to the `Wiring(...)` call. Add imports: `GoldWindowPolicy`, `RecomputeTrainingWeightUseCase`, `PgMaintainerRoleRepository`, `UserRoleChangedConsumer`.

- [ ] **Step 8: Extend `Wiring.kt`** — add the field and import:

```kotlin
    val userRoleChangedConsumer: UserRoleChangedConsumer? = null,
```

- [ ] **Step 9: Extend `Module.kt` shutdown hook**

```kotlin
    monitor.subscribe(ApplicationStopped) {
        wiring.userDeletedConsumer?.stop()
        wiring.userRoleChangedConsumer?.stop()
        wiring.closeNats()
    }
```

### 6e — worker `Main.kt`

- [ ] **Step 10: Extend bootstrap + add the recompute subcommand**

In `runBootstrapConsumer()`, after the existing `UserDeletedConsumerConfig.bootstrap(conn)` add `UserRoleChangedConsumerConfig.bootstrap(conn)` and log both durables. Add a `when` branch `"--recompute-training-weights" -> runRecompute()` and the function:

```kotlin
private fun runRecompute(): Int {
    val ds = openDataSource()
    val cutoff = System.getenv("SURVEY_GOLD_CUTOFF")?.let(Instant::parse) ?: Instant.parse("2026-05-30T00:00:00Z")
    val multiplier = System.getenv("SURVEY_GOLD_MULTIPLIER")?.toDoubleOrNull() ?: 3.0
    runBlocking {
        val items = PgSurveyItemRepository(ds)
        val roles = PgMaintainerRoleRepository(ds)
        RecomputeTrainingWeightUseCase(roles, items, GoldWindowPolicy(cutoff, multiplier)).recomputeAll()
    }
    log.info("event=recompute_training_weights_done")
    return 0
}
```

Update `printUsage()` to list `--recompute-training-weights`. Add imports: `UserRoleChangedConsumerConfig`, `PgMaintainerRoleRepository`, `RecomputeTrainingWeightUseCase`, `GoldWindowPolicy`.

### 6f — chart

- [ ] **Step 11: Add gold env to `values.yaml`** (under the `env:` list):

```yaml
  - name: SURVEY_GOLD_CUTOFF
    value: "2026-05-30T00:00:00Z"
  - name: SURVEY_GOLD_MULTIPLIER
    value: "3.0"
```

(`deployment.yaml` and `job-bootstrap-consumer.yaml` already `range` over `.Values.env`, so the worker bootstrap Job and api pod both pick these up — no template change needed there.)

- [ ] **Step 12: Add the manual recompute Job** `job-recompute-weights.yaml`, gated off by default (operator flips it for a policy-change re-materialize), mirroring `job-bootstrap-consumer.yaml`:

```yaml
{{- if .Values.recomputeTrainingWeights.enabled }}
{{/* Manual re-materialize of training_weight under the current GoldWindowPolicy. */}}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "bliss-survey-api.fullname" . }}-recompute-weights
  annotations:
    "helm.sh/hook": "post-upgrade"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
    "helm.sh/hook-weight": "10"
  labels: {{- include "bliss-survey-api.labels" . | nindent 4 }}
spec:
  ttlSecondsAfterFinished: 600
  backoffLimit: 3
  template:
    metadata:
      labels: {{- include "bliss-survey-api.selectorLabels" . | nindent 8 }}
    spec:
      restartPolicy: OnFailure
      serviceAccountName: {{ include "bliss-survey-api.serviceAccountName" . }}
      securityContext: {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: recompute-weights
          image: {{ include "bliss-survey-api.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext: {{- toYaml .Values.containerSecurityContext | nindent 12 }}
          command: ["/app/bin/survey-worker"]
          args: ["--recompute-training-weights"]
          env:
            {{- range .Values.env }}
            - name: {{ .name }}
              value: {{ .value | quote }}
            {{- end }}
          {{- with .Values.envFromSecret }}
          envFrom:
            - secretRef: {name: {{ . }}}
          {{- end }}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - {name: tmp, emptyDir: {}}
{{- end }}
```

Add to `values.yaml`:

```yaml
recomputeTrainingWeights:
  enabled: false
```

- [ ] **Step 13: Run the full survey build + chart lint**

Run: `./gradlew :survey:application:test :survey:api:test :survey:worker:test :survey:infrastructure:test --parallel`
Then: `helm lint survey/api/deploy/chart`
Expected: all green; Konsist arch tests in each module pass.

- [ ] **Step 14: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/usecases/AnonymizeUserRatingsUseCase.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/config/SurveyApiConfig.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt \
        survey/worker/src/main/kotlin/com/bliss/survey/worker/Main.kt \
        survey/api/deploy/chart/values.yaml \
        survey/api/deploy/chart/templates/job-recompute-weights.yaml \
        survey/application/src/test survey/api/src/test
git commit -s -m "feat(survey): stamp training_weight on role events, proposals, and manual recompute"
```

---

## PR sequencing (phase map)

Each phase leaves `./gradlew build` green. Dispatch one PR per phase; stack only where noted. The 423-on-lock and consumer slices from the survey-module rollout are the precedent for this layering.

| Phase | Branch | Base | Tasks | Layer(s) | Notes |
|---|---|---|---|---|---|
| B1 | `feat/survey-training-weight-migration` | `main` | Task 1 | infrastructure | V8 migration + Pg verification. |
| B2 | `feat/survey-gold-window-policy` | `main` | Task 2 | domain | Independent of B1; can run in parallel. |
| B3 | `feat/survey-training-weight-repos` | `main` | Task 3 | application + infrastructure | Interface change + `MaintainerRoleRepository` + Pg adapters. Depends on B1 (migration on `main`) for the Pg test. |
| B4 | `feat/survey-recompute-use-case` | `main` | Task 4 | application | Depends on B2 + B3 merged. |
| B5 | `feat/survey-role-changed-consumer` | `main` | Task 5 | infrastructure | Depends on B4 merged (consumer ctor takes the use case). |
| B6 | `feat/survey-training-weight-wiring` | `main` | Task 6 | application + api + worker + chart | Depends on B4 + B5 merged. **Cap-override expected** — coherent integration layer; splitting would leave half-wired constructors. Cite ADR-0001 §4 soft-target in the PR body. |

B1 and B2 are the only pair safe to open simultaneously (disjoint files, no shared module). Everything after B3 is strictly sequential because each consumes the prior layer's types.

**No companion ADR required** (per the spec): this is a consumer-side change within one bounded context, reusing the ADR-0049 consumer pattern and the `UserRoleChanged` contract Spec A already shipped. If B6's chart/Job work surfaces a cross-cutting decision, raise an ADR then.

---

## Self-review

**Spec coverage:** V8 column + `maintainer_roles` (Task 1) ✓; `GoldWindowPolicy` with inclusive cutoff (Task 2) ✓; `MaintainerRoleRepository` + `TrainingWeightWriter` via `updateTrainingWeight` (Task 3) ✓; `RecomputeTrainingWeightUseCase` with all three triggers — role event, new proposal, manual (Tasks 4 + 6) ✓; `UserRoleChangedConsumer` + config mirroring `UserDeleted` (Task 5) ✓; out-of-order `changed_at` guard (Task 4) ✓; UserDeleted erases the role (Task 6a) ✓; worker `--recompute-training-weights` + extended `--bootstrap-consumer` (Task 6e) ✓; consumer started before Ktor / stopped on shutdown (Task 6d) ✓; gold multiplier as config not hard-coded (Task 6c/6f) ✓.

**Type consistency:** `weightFor(createdAt: Instant, isMaintainer: Boolean): Double`, `updateTrainingWeight(id: ItemId, weight: Double)`, `MaintainerRole(userId, role: String, changedAt: Instant)`, and `onRoleChanged/forItem/recomputeAll` signatures are identical across the use case, its test, the consumer, and the wiring. `role` stays a wire `String` (`"player"`/`"maintainer"`) end-to-end; the gold check is `role == "maintainer"`.

**Out of scope (unchanged here):** export reading the weight (Spec C), corpus wiring (Spec D), any identity-side change, any change to the `UserRoleChanged` wire contract.
