# Survey Rating Undo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Backend Kotlin tasks: also load the `jvm-backend` skill for Konsist/Spotless/Gradle gotchas.

**Goal:** Add a server-side, durable, single-level undo for `/sondage` rating actions, undoable for the campaign's open lifetime plus an 8s close grace, exposed as a persistent on-card `Annuler` control.

**Architecture:** A new `survey_actions` log row is written in the same transaction as each rating submit, carrying a reversal recipe and the SHA-256 hash of a capability token returned to the client. `POST /v1/actions/undo` hashes the presented token, authorizes (session-binding for authed actions, possession for anon), checks the campaign-close grace, and reverses the recipe in one transaction. Settling for export is derived from campaign status. Introduces the survey context's first real transaction boundary via an ambient-connection coroutine-context element.

**Tech Stack:** Kotlin 2.3 + Ktor 3 + Postgres/Flyway/JDBC + kotlinx-coroutines (backend); Vite + React 19 + TanStack Router + Panda + Vitest/MSW (frontend). Spec: `docs/superpowers/specs/2026-05-30-survey-undo-design.md`.

**PR sequence (each ≤400 lines diff, ADR-0001 §4):**
- **PR 0.5 — ADR-0059 amendment:** amend `docs/adr/0059-*.md` with undo-window + export-at-close decisions; update `docs/adr/INDEX.md`. Docs-only; gates Phase 1 (ADR-0001 §7). (Phase 0.5)
- **PR 1 — schema-only:** `openapi.yaml` additive `undoToken` + `POST /v1/actions/undo`. (Phase 1)
- **PR 2a — transaction boundary:** `TransactionManager` port + adapter + ambient-connection refactor of the 5 write repos. No behavior change. (Phase 2)
- **PR 2b — action log:** V8 migration, `SurveyAction` domain, `ActionLogRepository` port + adapter, token util, new repo methods (`deleteByIds`, `findById`, `delete`, `decrementItemsRated`). (Phase 3)
- **PR 2c — undo + submit wiring:** `UndoActionUseCase`, submit use-cases mint token + write recipe in a transaction, export settling filter, route, DI wiring. (Phase 4)
- **PR 3 — frontend:** regenerate types, client `undoAction` + `undoToken`, persistent on-card `Annuler` on both sondage routes, analytics, tests. (Phase 5)

> The backend is split into 2a/2b/2c to stay under the diff cap; if the maintainer prefers a single backend PR, invoke the standing cap-override (memory: `feedback-standing-cap-override`).

---

## Phase 0.5 — ADR-0059 amendment PR (docs-only)

**Must merge before Phase 1.** ADR-0001 §7 requires the ADR to exist before any implementation PR. The undo-window and export-settling decisions are foundational to every downstream phase.

**Files:**
- Modify: `docs/adr/0059-<slug>.md`
- Modify: `docs/adr/INDEX.md`

### Task 0.5.1: Amend ADR-0059

- [ ] **Step 1: Add the amendment section**

In `docs/adr/0059-*.md`, append a dated amendment section recording:

(a) **Undo window** — an action is undoable for as long as its campaign is open plus a `CLOSE_GRACE = 8 s` tail after close. There is no per-action wall-clock timer during an open campaign.

(b) **Undo during close grace** — `UndoActionUseCase` intentionally allows undo during the grace even though new rating POSTs are 423-locked; the `campaign.closedAt` + grace check is the only gate.

(c) **Export settles per-campaign-at-close** — because undo is campaign-long, `aggregateForExport` excludes ratings whose campaign is not closed past the grace; export is effectively per-campaign-at-close, not continuous mid-campaign.

(d) **`proposed_item_id` recipe column** — the `survey_actions` reversal recipe carries a `proposed_item_id` column (in addition to `created_item_id`) to unwind the `proposed_by` row for a text-correctif where the item was *reused* (conflict on `(mot, definition)`) rather than freshly inserted.

- [ ] **Step 2: Update INDEX.md**

Add entries for the new use-case paths under the ADR-0059 and ADR-0056 rows in `docs/adr/INDEX.md`:
- `survey/**/usecases/UndoActionUseCase.kt` → ADR-0059, ADR-0056
- `survey/**/persistence/PgActionLogRepository.kt` → ADR-0059, ADR-0056

Run `scripts/adr-context.sh docs/adr/0059-*.md` to confirm the mapping format, then follow the existing row style.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0059-*.md docs/adr/INDEX.md
git commit -s -m "docs(adr): amend ADR-0059 with undo grace-window, export-at-close, proposed_item_id"
```

> PR 0.5 ends here. It merges before Phase 1 dispatch.

---

## Phase 1 — Schema-only PR (`openapi.yaml`)

**Files:**
- Modify: `survey/api/openapi.yaml`

### Task 1.1: Add `undoToken` to `RatingResponse` and `PairRatingResponse`

- [ ] **Step 1: Edit `RatingResponse` schema**

In `survey/api/openapi.yaml`, locate `components.schemas.RatingResponse` (around line 474). Add the `undoToken` property after `campaignId`:

```yaml
    RatingResponse:
      type: object
      required: [ratingId, itemId, submittedAs, campaignId]
      properties:
        ratingId:
          type: string
          format: uuid
        itemId:
          type: string
          format: uuid
        submittedAs:
          type: string
          enum: [auth, anon]
        proposedItemId:
          type: string
          format: uuid
          nullable: true
        campaignId:
          type: string
          format: uuid
        undoToken:
          type: string
          nullable: true
          description: >-
            Capability token for undoing this action. Non-null on 201 (fresh
            action); null on 409 (idempotent re-rate). Present it in the body of
            POST /v1/actions/undo.
```

> Match the surrounding indentation and the existing property style exactly — copy the real `required` list and existing property nodes from the file rather than the abbreviation above if they differ.

- [ ] **Step 2: Edit `PairRatingResponse` schema**

Locate `components.schemas.PairRatingResponse` (around line 487). Add `undoToken`:

```yaml
    PairRatingResponse:
      type: object
      required: [campaignId]
      properties:
        campaignId:
          type: string
          format: uuid
        undoToken:
          type: string
          nullable: true
          description: >-
            Capability token for undoing this pair action. Non-null on 201; null
            on 204 (SKIP, no body). Present it in the body of POST /v1/actions/undo.
```

- [ ] **Step 3: Verify YAML still lints**

Run: `npx @redocly/cli lint survey/api/openapi.yaml` (or the repo's `openapi-lint` command from `.github/workflows/openapi-lint.yml`).
Expected: no new errors.

### Task 1.2: Add the `POST /v1/actions/undo` path

- [ ] **Step 1: Add the path**

Under `paths:` in `survey/api/openapi.yaml`, add:

```yaml
  /v1/actions/undo:
    post:
      operationId: undoAction
      summary: Undo the action identified by a capability token.
      tags: [survey]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UndoActionRequest'
      responses:
        '204':
          description: Action reversed.
        '404':
          description: Token unknown, not yours, or already undone.
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/ProblemDetails'
        '410':
          description: Campaign-close grace window has elapsed.
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/ProblemDetails'
```

- [ ] **Step 2: Add the `UndoActionRequest` schema**

Under `components.schemas`, add:

```yaml
    UndoActionRequest:
      type: object
      required: [token]
      properties:
        token:
          type: string
          description: The capability token returned as undoToken by the submit response.
```

> If a `ProblemDetails` schema does not already exist in this file, copy the exact one used by the 423/404 responses on the existing rating paths (it does exist — the rating paths `$ref` it).

- [ ] **Step 3: Lint again**

Run: `npx @redocly/cli lint survey/api/openapi.yaml`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add survey/api/openapi.yaml
git commit -s -m "feat(api-survey): add undoToken field + POST /v1/actions/undo schema"
```

> PR 1 ends here. It merges before any consumer work (ADR-0003 schema-first). The frontend type regeneration happens in Phase 5, after this is on main.

---

## Phase 2 — Transaction boundary PR (2a)

The survey context has no cross-repo transaction today; every repo method opens its own `dataSource.connection.use {}`. This phase adds a `TransactionManager` that binds one `Connection` into the coroutine context, and refactors the write repos to use that ambient connection when present. **No behavior change** — every existing test must still pass, and the refactor is a pure connection-acquisition swap.

**Files:**
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/TransactionManager.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/TxConnection.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManager.kt`
- Modify: `PgRatingRepository.kt`, `PgPairRatingRepository.kt`, `PgSurveyItemRepository.kt`, `PgProposedByRepository.kt`, `PgUserProgressRepository.kt` (connection acquisition only)
- Test: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManagerTest.kt`

### Task 2a.1: Define the `TransactionManager` port

- [ ] **Step 1: Create the port**

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/TransactionManager.kt`:

```kotlin
package com.bliss.survey.application.ports

interface TransactionManager {
    /** Runs [block] inside one DB transaction. Commits on normal return, rolls back on throw. */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :survey:application:compileKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2a.2: Ambient-connection context element + helper

- [ ] **Step 1: Create `TxConnection.kt`**

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/TxConnection.kt`:

```kotlin
package com.bliss.survey.infrastructure.persistence

import java.sql.Connection
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Carries the in-flight transactional connection across coroutine boundaries. */
internal class TxConnection(
    val connection: Connection,
) : AbstractCoroutineContextElement(TxConnection) {
    companion object Key : CoroutineContext.Key<TxConnection>
}

/** Returns the ambient tx connection if one is bound, otherwise borrows a fresh pooled one; does NOT close the ambient connection. */
internal suspend fun <T> withTxConnection(
    dataSource: DataSource,
    block: (Connection) -> T,
): T {
    val ambient = coroutineContext[TxConnection]?.connection
    return if (ambient != null) block(ambient) else dataSource.connection.use(block)
}
```

- [ ] **Step 2: Create `PgTransactionManager.kt`**

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManager.kt`:

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.TransactionManager
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PgTransactionManager(
    private val dataSource: DataSource,
) : TransactionManager {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val result = withContext(TxConnection(conn)) { block() }
                    conn.commit()
                    result
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :survey:infrastructure:compileKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2a.3: Refactor the 5 write repos to use `withTxConnection`

For each repo file, replace every `dataSource.connection.use { conn ->` with `withTxConnection(dataSource) { conn ->` (the `withContext(Dispatchers.IO)` wrapper stays; the closing brace structure is identical). Do **not** change constructors — repos keep their `dataSource: DataSource` field.

- [ ] **Step 1: `PgRatingRepository.kt`** — replace in `findAuthRating`, `insert`, `anonymiseForUser`, `aggregateForExport`, `countByItem`, and any other method using the pattern.

Example transformation for `insert`:

```kotlin
override suspend fun insert(rating: Rating): Unit =
    withContext(Dispatchers.IO) {
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                // ... unchanged body ...
                stmt.executeUpdate()
            }
        }
    }
```

- [ ] **Step 2: `PgPairRatingRepository.kt`** — same swap in every method.
- [ ] **Step 3: `PgSurveyItemRepository.kt`** — same swap (`insertIfAbsent`, `updatePos`, `deleteByIds`, `findById`, the saturated query, etc.).
- [ ] **Step 4: `PgProposedByRepository.kt`** — same swap.
- [ ] **Step 5: `PgUserProgressRepository.kt`** — same swap.

- [ ] **Step 6: Compile + Spotless**

Run: `./gradlew :survey:infrastructure:compileKotlin spotlessCheck`
Expected: BUILD SUCCESSFUL. (Run `./gradlew spotlessApply` if formatting fails.)

### Task 2a.4: Transaction-manager integration test (Testcontainers)

- [ ] **Step 1: Write the failing test**

`survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManagerTest.kt`. Mirror the Testcontainers setup used by the other `Pg*RepositoryTest` files in this directory (same container/Flyway bootstrap helper). Two cases:

```kotlin
@Test
fun `commits both writes when block succeeds`() = runBlocking {
    val tx = PgTransactionManager(dataSource)
    val items = PgSurveyItemRepository(dataSource)
    val ratings = PgRatingRepository(dataSource)
    val item = sampleItem() // helper that builds + persists a parent item, mirror existing tests
    items.insertIfAbsent(item)

    tx.inTransaction {
        ratings.insert(sampleRating(item.id))
    }

    assertThat(ratings.countByItem(item.id)).isEqualTo(1)
}

@Test
fun `rolls back all writes when block throws`() = runBlocking {
    val tx = PgTransactionManager(dataSource)
    val items = PgSurveyItemRepository(dataSource)
    val ratings = PgRatingRepository(dataSource)
    val item = sampleItem()
    items.insertIfAbsent(item)

    assertThatThrownBy {
        runBlocking {
            tx.inTransaction {
                ratings.insert(sampleRating(item.id))
                error("boom")
            }
        }
    }.hasMessageContaining("boom")

    assertThat(ratings.countByItem(item.id)).isEqualTo(0) // rolled back
}
```

> Use the exact `sampleItem()` / `sampleRating()` fixture helpers (or inline builders) already present in the sibling repository tests — copy their construction so column constraints are satisfied.

- [ ] **Step 2: Run — expect FAIL first if helpers missing, then PASS once wired**

Run: `./gradlew :survey:infrastructure:test --tests '*PgTransactionManagerTest'`
Expected: both tests PASS (rollback case proves atomicity — the key guarantee).

- [ ] **Step 3: Full module test (no regressions)**

Run: `./gradlew :survey:infrastructure:test`
Expected: BUILD SUCCESSFUL — the repo refactor changed no behavior.

- [ ] **Step 4: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/ports/TransactionManager.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/TxConnection.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManager.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/Pg*Repository.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTransactionManagerTest.kt
git commit -s -m "feat(survey-infrastructure): add TransactionManager + ambient-connection boundary"
```

---

## Phase 3 — Action log PR (2b)

The `survey_actions` table, its domain model, repository port + adapter, a token generator/hash, and the extra repo methods the reversal will call. No use-case wiring yet — that is Phase 4. Each piece is independently compilable and testable.

**Files:**
- Create: `survey/infrastructure/src/main/resources/db/migration/V8__survey_actions.sql`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyAction.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/ActionLogRepository.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/TokenGenerator.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/UndoTokenHash.kt`
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgActionLogRepository.kt`
- Modify: `RatingRepository.kt` + `PgRatingRepository.kt` (add `deleteByIds`)
- Modify: `PairRatingRepository.kt` + `PgPairRatingRepository.kt` (add `deleteById`)
- Modify: `ProposedByRepository.kt` + `PgProposedByRepository.kt` (add `delete(itemId, userId)`)
- Modify: `UserProgressRepository.kt` + `PgUserProgressRepository.kt` (add `decrementItemsRated`)
- Modify: `CampaignRepository.kt` + `PgCampaignRepository.kt` (add `findById`)
- Test: `PgActionLogRepositoryTest.kt`, `UndoTokenHashTest.kt`, and additions to existing repo tests.

> **Spec refinement:** the migration adds a `proposed_item_id` column not enumerated in the spec's data-model block. It is needed to delete the `proposed_by` row on undo of a text-correctif when the item was *reused* (so `created_item_id` is null but a `proposed_by` row still exists). Note this in the ADR-0059 amendment in Phase 0.5 (already captured in Task 0.5.1 step 1(d)).

### Task 2b.1: V8 migration

- [ ] **Step 1: Create the migration**

`survey/infrastructure/src/main/resources/db/migration/V8__survey_actions.sql`:

```sql
CREATE TABLE survey_actions (
    action_id           UUID PRIMARY KEY,
    undo_token_hash     BYTEA NOT NULL UNIQUE,
    user_id             UUID,
    kind                TEXT NOT NULL CHECK (kind IN ('binary', 'pair', 'correctif')),
    campaign_id         UUID NOT NULL REFERENCES campaigns(campaign_id),
    created_at          TIMESTAMPTZ NOT NULL,
    undone_at           TIMESTAMPTZ,
    created_rating_ids  UUID[] NOT NULL DEFAULT '{}',
    created_pair_id     UUID,
    created_item_id     UUID,
    proposed_item_id    UUID,
    patched_item_id     UUID,
    prior_pos           TEXT,
    prior_last_rated_at TIMESTAMPTZ
);

CREATE INDEX survey_actions_campaign_idx ON survey_actions (campaign_id);
```

- [ ] **Step 2: Verify Flyway applies it**

Run: `./gradlew :survey:infrastructure:test --tests '*FlywayMigration*'` if such a test exists, otherwise rely on the Testcontainers bootstrap in the next task (which runs all migrations). Expected: migration applies with no error, version becomes 8.

### Task 2b.2: `SurveyAction` domain model

- [ ] **Step 1: Create the model**

`survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyAction.kt`:

```kotlin
package com.bliss.survey.domain.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class ActionId(val value: UUID)

enum class ActionKind { BINARY, PAIR, CORRECTIF }

/** One reversible rating submit. `undoTokenHash` = sha256(capability token). */
data class SurveyAction(
    val id: ActionId,
    val undoTokenHash: ByteArray,
    val userId: UserId?,
    val kind: ActionKind,
    val campaignId: CampaignId,
    val createdAt: Instant,
    val undoneAt: Instant?,
    val createdRatingIds: List<RatingId>,
    val createdPairId: PairRatingId?,
    val createdItemId: ItemId?,
    val proposedItemId: ItemId?,
    val patchedItemId: ItemId?,
    val priorPos: Pos?,
    val priorLastRatedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SurveyAction) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
```

> The custom `equals`/`hashCode` exist because `ByteArray` uses reference equality in a data class — identity on `id` is what callers want and silences the Konsist/detekt array-in-data-class warning.

- [ ] **Step 2: Compile**

Run: `./gradlew :survey:domain:compileKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2b.3: Token generator port + hash util

- [ ] **Step 1: Create `TokenGenerator` port**

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/TokenGenerator.kt`:

```kotlin
package com.bliss.survey.application.ports

fun interface TokenGenerator {
    /** Returns a fresh high-entropy, URL-safe capability token (~256 bits). */
    fun newToken(): String
}
```

- [ ] **Step 2: Write the failing hash-util test**

`survey/application/src/test/kotlin/com/bliss/survey/application/UndoTokenHashTest.kt`:

```kotlin
package com.bliss.survey.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UndoTokenHashTest {
    @Test
    fun `is deterministic and 32 bytes`() {
        val a = sha256("token-abc")
        val b = sha256("token-abc")
        assertThat(a).isEqualTo(b)
        assertThat(a).hasSize(32)
    }

    @Test
    fun `differs for different tokens`() {
        assertThat(sha256("x")).isNotEqualTo(sha256("y"))
    }
}
```

- [ ] **Step 3: Run — expect FAIL (unresolved `sha256`)**

Run: `./gradlew :survey:application:test --tests '*UndoTokenHashTest'`
Expected: compile failure / unresolved reference.

- [ ] **Step 4: Implement**

`survey/application/src/main/kotlin/com/bliss/survey/application/UndoTokenHash.kt`:

```kotlin
package com.bliss.survey.application

import java.security.MessageDigest

fun sha256(token: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
```

- [ ] **Step 5: Run — expect PASS**

Run: `./gradlew :survey:application:test --tests '*UndoTokenHashTest'`
Expected: PASS.

### Task 2b.4: `ActionLogRepository` port

- [ ] **Step 1: Create the port**

`survey/application/src/main/kotlin/com/bliss/survey/application/ports/ActionLogRepository.kt`:

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface ActionLogRepository {
    suspend fun insert(action: SurveyAction)

    suspend fun findByTokenHash(tokenHash: ByteArray): SurveyAction?

    suspend fun markUndone(id: ActionId, at: Instant)

    /** RGPD: null out user_id for the erased user's actions. */
    suspend fun scrubUser(userId: UserId)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :survey:application:compileKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2b.5: `PgActionLogRepository` adapter + test

- [ ] **Step 1: Write the failing test**

`survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgActionLogRepositoryTest.kt`. Mirror the sibling tests' Testcontainers/Flyway setup. Insert a parent campaign first (FK), then:

```kotlin
@Test
fun `round-trips an action and finds it by token hash`() = runBlocking {
    val repo = PgActionLogRepository(dataSource)
    val campaign = persistOpenCampaign() // mirror existing campaign-test helper
    val action = sampleBinaryAction(campaignId = campaign.id, tokenHash = sha256("tok"))
    repo.insert(action)

    val found = repo.findByTokenHash(sha256("tok"))
    assertThat(found?.id).isEqualTo(action.id)
    assertThat(found?.createdRatingIds).isEqualTo(action.createdRatingIds)
    assertThat(repo.findByTokenHash(sha256("other"))).isNull()
}

@Test
fun `markUndone stamps undone_at`() = runBlocking {
    val repo = PgActionLogRepository(dataSource)
    val campaign = persistOpenCampaign()
    val action = sampleBinaryAction(campaign.id, sha256("tok2"))
    repo.insert(action)

    repo.markUndone(action.id, Instant.parse("2026-05-30T12:00:00Z"))

    assertThat(repo.findByTokenHash(sha256("tok2"))?.undoneAt)
        .isEqualTo(Instant.parse("2026-05-30T12:00:00Z"))
}

@Test
fun `scrubUser nulls user_id`() = runBlocking {
    val repo = PgActionLogRepository(dataSource)
    val campaign = persistOpenCampaign()
    val uid = UserId(UUID.randomUUID())
    repo.insert(sampleBinaryAction(campaign.id, sha256("tok3")).copy(userId = uid))

    repo.scrubUser(uid)

    assertThat(repo.findByTokenHash(sha256("tok3"))?.userId).isNull()
}
```

- [ ] **Step 2: Run — expect FAIL (class missing)**

Run: `./gradlew :survey:infrastructure:test --tests '*PgActionLogRepositoryTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the adapter**

`survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgActionLogRepository.kt`:

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.ActionLogRepository
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairRatingId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PgActionLogRepository(
    private val dataSource: DataSource,
) : ActionLogRepository {
    override suspend fun insert(action: SurveyAction): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, action.id.value)
                    stmt.setBytes(2, action.undoTokenHash)
                    if (action.userId != null) stmt.setObject(3, action.userId.value) else stmt.setNull(3, Types.OTHER)
                    stmt.setString(4, action.kind.name.lowercase())
                    stmt.setObject(5, action.campaignId.value)
                    stmt.setTimestamp(6, Timestamp.from(action.createdAt))
                    val ratingIds = action.createdRatingIds.map { it.value }.toTypedArray()
                    stmt.setArray(7, conn.createArrayOf("uuid", ratingIds))
                    stmt.setObjectOrNull(8, action.createdPairId?.value)
                    stmt.setObjectOrNull(9, action.createdItemId?.value)
                    stmt.setObjectOrNull(10, action.proposedItemId?.value)
                    stmt.setObjectOrNull(11, action.patchedItemId?.value)
                    if (action.priorPos != null) stmt.setString(12, action.priorPos.name.lowercase()) else stmt.setNull(12, Types.VARCHAR)
                    if (action.priorLastRatedAt != null) stmt.setTimestamp(13, Timestamp.from(action.priorLastRatedAt)) else stmt.setNull(13, Types.TIMESTAMP)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun findByTokenHash(tokenHash: ByteArray): SurveyAction? =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(FIND_BY_HASH_SQL).use { stmt ->
                    stmt.setBytes(1, tokenHash)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSurveyAction() else null }
                }
            }
        }

    override suspend fun markUndone(id: ActionId, at: Instant): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(MARK_UNDONE_SQL).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(at))
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }

    override suspend fun scrubUser(userId: UserId): Unit =
        withContext(Dispatchers.IO) {
            withTxConnection(dataSource) { conn ->
                conn.prepareStatement(SCRUB_USER_SQL).use { stmt ->
                    stmt.setObject(1, userId.value)
                    stmt.executeUpdate()
                }
            }
        }

    private fun java.sql.PreparedStatement.setObjectOrNull(idx: Int, value: UUID?) {
        if (value != null) setObject(idx, value) else setNull(idx, Types.OTHER)
    }

    private fun ResultSet.toSurveyAction(): SurveyAction {
        val ratingArray = getArray("created_rating_ids")
        val ratingIds = (ratingArray.array as Array<*>).map { RatingId(it as UUID) }
        return SurveyAction(
            id = ActionId(getObject("action_id", UUID::class.java)),
            undoTokenHash = getBytes("undo_token_hash"),
            userId = getObject("user_id", UUID::class.java)?.let { UserId(it) },
            kind = ActionKind.valueOf(getString("kind").uppercase()),
            campaignId = CampaignId(getObject("campaign_id", UUID::class.java)),
            createdAt = getTimestamp("created_at").toInstant(),
            undoneAt = getTimestamp("undone_at")?.toInstant(),
            createdRatingIds = ratingIds,
            createdPairId = getObject("created_pair_id", UUID::class.java)?.let { PairRatingId(it) },
            createdItemId = getObject("created_item_id", UUID::class.java)?.let { ItemId(it) },
            proposedItemId = getObject("proposed_item_id", UUID::class.java)?.let { ItemId(it) },
            patchedItemId = getObject("patched_item_id", UUID::class.java)?.let { ItemId(it) },
            priorPos = getString("prior_pos")?.let { Pos.valueOf(it.uppercase()) },
            priorLastRatedAt = getTimestamp("prior_last_rated_at")?.toInstant(),
        )
    }

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO survey_actions
              (action_id, undo_token_hash, user_id, kind, campaign_id, created_at,
               created_rating_ids, created_pair_id, created_item_id, proposed_item_id,
               patched_item_id, prior_pos, prior_last_rated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        const val FIND_BY_HASH_SQL = "SELECT * FROM survey_actions WHERE undo_token_hash = ?"
        const val MARK_UNDONE_SQL = "UPDATE survey_actions SET undone_at = ? WHERE action_id = ?"
        const val SCRUB_USER_SQL = "UPDATE survey_actions SET user_id = NULL WHERE user_id = ?"
    }
}
```

> Confirm the `Pos` enum stores lowercase in other tables (it does — `updatePos` writes `pos.name.lowercase()`). Keep that convention for `prior_pos`.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :survey:infrastructure:test --tests '*PgActionLogRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/infrastructure/src/main/resources/db/migration/V8__survey_actions.sql \
        survey/domain/src/main/kotlin/com/bliss/survey/domain/model/SurveyAction.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/ports/ActionLogRepository.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/ports/TokenGenerator.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/UndoTokenHash.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/UndoTokenHashTest.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgActionLogRepository.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgActionLogRepositoryTest.kt
git commit -s -m "feat(survey): add survey_actions table, SurveyAction model, ActionLogRepository"
```

### Task 2b.6: Add the reversal repo methods

Each is a port addition + adapter impl + a focused test. Keep them in one commit.

- [ ] **Step 1: `RatingRepository.deleteByIds`**

Add to `RatingRepository.kt`:

```kotlin
suspend fun deleteByIds(ids: List<RatingId>)
```

Impl in `PgRatingRepository.kt`:

```kotlin
override suspend fun deleteByIds(ids: List<RatingId>): Unit =
    withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement("DELETE FROM ratings WHERE rating_id = ANY(?)").use { stmt ->
                stmt.setArray(1, conn.createArrayOf("uuid", ids.map { it.value }.toTypedArray()))
                stmt.executeUpdate()
            }
        }
    }
```

> Confirm the ratings PK column name (`rating_id`) from `V2__ratings.sql`; adjust if different.

- [ ] **Step 2: `PairRatingRepository.deleteById`**

Add `suspend fun deleteById(id: PairRatingId)` + impl:

```kotlin
override suspend fun deleteById(id: PairRatingId): Unit =
    withContext(Dispatchers.IO) {
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement("DELETE FROM pair_ratings WHERE pair_rating_id = ?").use { stmt ->
                stmt.setObject(1, id.value)
                stmt.executeUpdate()
            }
        }
    }
```

> Confirm `pair_ratings` PK column from `V6__pair_ratings.sql`.

- [ ] **Step 3: `ProposedByRepository.delete(itemId, userId)`**

Add `suspend fun delete(itemId: ItemId, userId: UserId)` + impl:

```kotlin
override suspend fun delete(itemId: ItemId, userId: UserId): Unit =
    withContext(Dispatchers.IO) {
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement("DELETE FROM proposed_by WHERE item_id = ? AND user_id = ?").use { stmt ->
                stmt.setObject(1, itemId.value)
                stmt.setObject(2, userId.value)
                stmt.executeUpdate()
            }
        }
    }
```

> Confirm `proposed_by` column names from `V3__proposed_by.sql`.

- [ ] **Step 4: `CampaignRepository.findById`**

Add `suspend fun findById(id: CampaignId): Campaign?` + impl (mirror `findOpen` but `WHERE campaign_id = ?`):

```kotlin
override suspend fun findById(id: CampaignId): Campaign? =
    withContext(Dispatchers.IO) {
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement("SELECT * FROM campaigns WHERE campaign_id = ?").use { stmt ->
                stmt.setObject(1, id.value)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toCampaign() else null }
            }
        }
    }
```

> Reuse the existing private `rs.toCampaign()` mapper in `PgCampaignRepository`.

- [ ] **Step 5: `UserProgressRepository.decrementItemsRated`**

Add to port:

```kotlin
suspend fun decrementItemsRated(userId: UserId, by: Int, priorLastRatedAt: Instant?)
```

Impl in `PgUserProgressRepository.kt`:

```kotlin
override suspend fun decrementItemsRated(
    userId: UserId,
    by: Int,
    priorLastRatedAt: Instant?,
): Unit =
    withContext(Dispatchers.IO) {
        withTxConnection(dataSource) { conn ->
            conn.prepareStatement(DECREMENT_SQL).use { stmt ->
                stmt.setInt(1, by)
                if (priorLastRatedAt != null) stmt.setTimestamp(2, Timestamp.from(priorLastRatedAt)) else stmt.setNull(2, Types.TIMESTAMP)
                stmt.setObject(3, userId.value)
                stmt.executeUpdate()
            }
        }
    }
```

Add the SQL constant:

```kotlin
const val DECREMENT_SQL =
    """
    UPDATE user_progress
       SET items_rated = GREATEST(items_rated - ?, 0),
           last_rated_at = ?
     WHERE user_id = ?
    """
```

- [ ] **Step 6: Write focused tests for each new method**

Add to the relevant existing `Pg*RepositoryTest` files: insert-then-delete round-trips (assert count 0), `findById` returns the persisted campaign, `decrementItemsRated(uid, 2, prior)` floors at 0 and restores `last_rated_at`.

- [ ] **Step 7: Run + Spotless + commit**

Run: `./gradlew :survey:infrastructure:test spotlessCheck`
Expected: PASS.

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/ports/*.kt \
        survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/Pg*Repository.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/*Test.kt
git commit -s -m "feat(survey): add reversal repo methods (deleteByIds, deleteById, delete, findById, decrementItemsRated)"
```

---

## Phase 4 — Undo use case + submit wiring PR (2c)

The `UndoActionUseCase`, the submit use-cases minting a token and writing the recipe inside a transaction, the export settling filter, the route, DI wiring.

**Files:**
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/UndoActionUseCase.kt`
- Modify: `SubmitRatingUseCase.kt`, `SubmitPairRatingUseCase.kt`
- Modify: `AnonymizeUserRatingsUseCase.kt`
- Modify: `PgRatingRepository.kt` (export settling filter)
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/UndoActionRoute.kt`
- Modify: `survey/api/.../dto/RatingDtos.kt`, `Main.kt`, `Module.kt`, the `Wiring` data class
- Test: `UndoActionUseCaseTest.kt`, submit use-case test additions, `UndoActionRouteTest.kt`, export-filter test addition.

> ADR-0059 amendment and INDEX.md update shipped in Phase 0.5 (already on `main`).

### Task 2c.1: `UndoActionUseCase` (TDD, domain-level)

- [ ] **Step 1: Write the failing test**

`survey/application/src/test/kotlin/com/bliss/survey/application/usecases/UndoActionUseCaseTest.kt`. Use in-memory fakes for the repos (the survey app tests already use fakes — mirror them). The grace constant `CLOSE_GRACE = Duration.ofSeconds(8)`. Cases:

```kotlin
class UndoActionUseCaseTest {
    private val now = Instant.parse("2026-05-30T12:00:00Z")

    @Test
    fun `binary undo deletes rating, decrements progress, marks undone`() = runBlocking {
        // open campaign, one binary action with one created rating + progress=1
        val r = newFixture(campaignOpen = true)
        val result = r.useCase.execute(token = "tok", sessionUserId = r.userId)
        assertThat(result).isEqualTo(UndoActionResult.Undone)
        assertThat(r.ratings.all()).isEmpty()
        assertThat(r.progress.get(r.userId)?.itemsRated).isEqualTo(0)
        assertThat(r.actions.findByTokenHash(sha256("tok"))?.undoneAt).isEqualTo(now)
    }

    @Test
    fun `unknown token is NotFound`() = runBlocking {
        val r = newFixture(campaignOpen = true)
        assertThat(r.useCase.execute("nope", r.userId)).isEqualTo(UndoActionResult.NotFound)
    }

    @Test
    fun `authed action undone by a different user is NotFound`() = runBlocking {
        val r = newFixture(campaignOpen = true)
        assertThat(r.useCase.execute("tok", sessionUserId = UserId(UUID.randomUUID())))
            .isEqualTo(UndoActionResult.NotFound)
    }

    @Test
    fun `already-undone action is NotFound`() = runBlocking {
        val r = newFixture(campaignOpen = true)
        r.useCase.execute("tok", r.userId)
        assertThat(r.useCase.execute("tok", r.userId)).isEqualTo(UndoActionResult.NotFound)
    }

    @Test
    fun `closed campaign within grace is Undone`() = runBlocking {
        val r = newFixture(campaignOpen = false, closedAt = now.minusSeconds(5))
        assertThat(r.useCase.execute("tok", r.userId)).isEqualTo(UndoActionResult.Undone)
    }

    @Test
    fun `closed campaign past grace is Expired`() = runBlocking {
        val r = newFixture(campaignOpen = false, closedAt = now.minusSeconds(9))
        assertThat(r.useCase.execute("tok", r.userId)).isEqualTo(UndoActionResult.Expired)
    }

    @Test
    fun `anon action undone without a session`() = runBlocking {
        val r = newFixture(campaignOpen = true, anon = true)
        assertThat(r.useCase.execute("tok", sessionUserId = null)).isEqualTo(UndoActionResult.Undone)
    }

    @Test
    fun `text-correctif undo deletes both ratings, proposed_by, and fresh item with no other refs`() = runBlocking {
        val r = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, freshItem = true)
        r.useCase.execute("tok", r.userId)
        assertThat(r.items.findById(r.createdItemId!!)).isNull()
        assertThat(r.proposedBy.exists(r.createdItemId!!, r.userId)).isFalse()
    }

    @Test
    fun `text-correctif undo keeps item when another rating references it`() = runBlocking {
        val r = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, freshItem = true, otherRefExists = true)
        r.useCase.execute("tok", r.userId)
        assertThat(r.items.findById(r.createdItemId!!)).isNotNull()
    }

    @Test
    fun `POS-only correctif undo restores prior pos`() = runBlocking {
        val r = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, patched = true, priorPos = Pos.NOM_COMMUN)
        r.useCase.execute("tok", r.userId)
        assertThat(r.items.findById(r.patchedItemId!!)?.pos).isEqualTo(Pos.NOM_COMMUN)
    }

    @Test
    fun `pair BOTH_* undo decrements progress twice`() = runBlocking {
        val r = newFixture(campaignOpen = true, kind = ActionKind.PAIR, twoRatings = true, progressStart = 2)
        r.useCase.execute("tok", r.userId)
        assertThat(r.progress.get(r.userId)?.itemsRated).isEqualTo(0)
    }
}
```

> `newFixture(...)` is a test helper you write that seeds the in-memory fakes (`ActionLogRepository`, `RatingRepository`, `PairRatingRepository`, `SurveyItemRepository`, `ProposedByRepository`, `UserProgressRepository`, `CampaignRepository`), a fixed `Clock { now }`, and a no-op `TransactionManager` (`object : TransactionManager { override suspend fun <T> inTransaction(block) = block() }`). Build the `SurveyAction` with `undoTokenHash = sha256("tok")`.

- [ ] **Step 2: Run — expect FAIL (class missing)**

Run: `./gradlew :survey:application:test --tests '*UndoActionUseCaseTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the use case**

`survey/application/src/main/kotlin/com/bliss/survey/application/usecases/UndoActionUseCase.kt`:

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.ActionLogRepository
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.PairRatingRepository
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.TransactionManager
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.sha256
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId
import java.time.Duration

sealed interface UndoActionResult {
    data object Undone : UndoActionResult

    data object NotFound : UndoActionResult

    data object Expired : UndoActionResult
}

class UndoActionUseCase(
    private val actions: ActionLogRepository,
    private val ratings: RatingRepository,
    private val pairRatings: PairRatingRepository,
    private val items: SurveyItemRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val campaigns: CampaignRepository,
    private val tx: TransactionManager,
    private val clock: Clock,
) {
    suspend fun execute(token: String, sessionUserId: UserId?): UndoActionResult {
        val action = actions.findByTokenHash(sha256(token)) ?: return UndoActionResult.NotFound
        if (action.undoneAt != null) return UndoActionResult.NotFound
        // Authed actions bind to the session user; no existence leak (404, not 403).
        if (action.userId != null && action.userId != sessionUserId) return UndoActionResult.NotFound

        val campaign = campaigns.findById(action.campaignId) ?: return UndoActionResult.NotFound
        val now = clock.now()
        val closedAt = campaign.closedAt
        if (closedAt != null && now.isAfter(closedAt.plus(CLOSE_GRACE))) return UndoActionResult.Expired

        tx.inTransaction {
            reverse(action)
            actions.markUndone(action.id, now)
        }
        return UndoActionResult.Undone
    }

    private suspend fun reverse(action: SurveyAction) {
        if (action.createdRatingIds.isNotEmpty()) ratings.deleteByIds(action.createdRatingIds)
        action.createdPairId?.let { pairRatings.deleteById(it) }
        // POS-only correctif: restore the in-place mutation.
        if (action.patchedItemId != null && action.priorPos != null) {
            items.updatePos(action.patchedItemId, action.priorPos)
        }
        // Text correctif: remove our proposed_by link, then the item iff we created it and nothing else now references it.
        if (action.proposedItemId != null && action.userId != null) {
            proposedBy.delete(action.proposedItemId, action.userId)
        }
        if (action.createdItemId != null && ratings.countByItem(action.createdItemId) == 0) {
            items.deleteByIds(listOf(action.createdItemId))
        }
        // Progress: one decrement per created rating-bearing increment; pair BOTH_* incremented twice.
        if (action.userId != null) {
            val decrement = if (action.kind == ActionKind.PAIR && action.createdRatingIds.size == 2) 2 else 1
            progress.decrementItemsRated(action.userId, decrement, action.priorLastRatedAt)
        }
    }

    private companion object {
        val CLOSE_GRACE: Duration = Duration.ofSeconds(8)
    }
}
```

> `countByItem` must already exist on `RatingRepository` (it does — used by retirement). If the deleted ratings are still visible inside the transaction, the count works because `deleteByIds` ran first on the same ambient connection. `deleteByIds(listOf(...))` reuses the existing `SurveyItemRepository.deleteByIds`.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :survey:application:test --tests '*UndoActionUseCaseTest'`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/usecases/UndoActionUseCase.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/UndoActionUseCaseTest.kt
git commit -s -m "feat(survey-application): add UndoActionUseCase with campaign-grace + capability auth"
```

### Task 2c.2: Submit use-cases mint token + write recipe in a transaction

- [ ] **Step 1: Extend `SubmitRatingResult.Accepted` to carry the token**

In `SubmitRatingUseCase.kt`, change:

```kotlin
data class Accepted(
    val rating: Rating,
    val undoToken: String,
) : SubmitRatingResult
```

- [ ] **Step 2: Add the new collaborators to the constructor**

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
    private val actions: ActionLogRepository,
    private val tokens: TokenGenerator,
    private val tx: TransactionManager,
) {
```

- [ ] **Step 3: Capture `priorLastRatedAt`, assemble the recipe, wrap writes in `inTransaction`**

Rewrite the write tail of `execute(...)` (everything from the first DB write onward) so it runs inside `tx.inTransaction { }`, tracks the created rating ids / proposed item / patched item / prior pos, reads `progress.get(userId)?.lastRatedAt` **before** incrementing, mints a token, and inserts the action row. Sketch:

```kotlin
// (validation + filter logic above is unchanged and stays OUTSIDE the transaction)
val token = tokens.newToken()
val createdRatingIds = mutableListOf<RatingId>()
var createdItemId: ItemId? = null      // set only when insertIfAbsent inserted fresh
var proposedItemId: ItemId? = null     // set whenever the text-correctif path ran
var patchedItemId: ItemId? = null
var priorPos: Pos? = null

val priorLastRatedAt = if (cmd.userId != null) progress.get(cmd.userId)?.lastRatedAt else null

val rating = tx.inTransaction {
    if (cmd.correctif != null) {
        // POS-only branch:
        if (!textChanged) {
            if (requestedPos != null && requestedPos != parent.pos) {
                patchedItemId = parent.id
                priorPos = parent.pos
                items.updatePos(parent.id, requestedPos)
            }
        } else {
            // ... run filters BEFORE the transaction ideally; if kept here, a Reject must throw to roll back.
            val stored = items.insertIfAbsent(newItem)
            proposedItemId = stored.id
            if (stored.id == newItem.id) createdItemId = stored.id   // fresh insert kept its generated id
            proposedBy.insert(stored.id, nonNullUserId, optedOut = false)
        }
    }
    val r = Rating(/* unchanged, id = RatingId(ids.next()) */)
    ratings.insert(r)
    createdRatingIds += r.id
    if (proposedItemId != null) {
        val autoGood = Rating(/* unchanged */)
        ratings.insert(autoGood)
        createdRatingIds += autoGood.id
    }
    if (cmd.userId != null) progress.incrementItemsRated(cmd.userId, now)

    actions.insert(
        SurveyAction(
            id = ActionId(ids.next()),
            undoTokenHash = sha256(token),
            userId = cmd.userId,
            kind = if (cmd.correctif != null) ActionKind.CORRECTIF else ActionKind.BINARY,
            campaignId = openCampaign.id,
            createdAt = now,
            undoneAt = null,
            createdRatingIds = createdRatingIds.toList(),
            createdPairId = null,
            createdItemId = createdItemId,
            proposedItemId = proposedItemId,
            patchedItemId = patchedItemId,
            priorPos = priorPos,
            priorLastRatedAt = priorLastRatedAt,
        ),
    )
    r
}
return SubmitRatingResult.Accepted(rating, token)
```

> `createdItemId` distinguishes fresh-vs-reused: `insertIfAbsent` returns the input `item` unchanged when it inserted (same `id`), or the existing row (different `id`) on conflict — see its impl. Compare `stored.id == newItem.id`. Keep the `CorrectifRejected` early-return OUTSIDE the transaction (run `filters.run` before `tx.inTransaction`) so a rejection does not open/rollback a transaction.

- [ ] **Step 4: Mirror for `SubmitPairRatingUseCase`**

Add `actions`, `tokens`, `tx` to the constructor. Change `Recorded` to carry the token:

```kotlin
data class Recorded(
    val campaignId: CampaignId,
    val undoToken: String,
) : SubmitPairRatingResult
```

Wrap `insertPreference` / `insertAbsolutePair` writes in `tx.inTransaction`, collect the created pair id (preference) or the two rating ids (absolute), read `priorLastRatedAt` before incrementing, and insert a `SurveyAction` with `kind = ActionKind.PAIR`, `createdPairId` set for preference, `createdRatingIds` of size 2 for BOTH_*. Return the token in `Recorded`.

- [ ] **Step 5: Update the route mappers to surface the token**

In `SubmitRatingRoute.kt`, the `Accepted` branch:

```kotlin
is SubmitRatingResult.Accepted ->
    call.respond(HttpStatusCode.Created, result.rating.toResponse(undoToken = result.undoToken))
```

Update `Rating.toResponse()` (in the route file or DTO mapper) to accept `undoToken: String? = null` and set it on `RatingResponse`. The `AlreadyExists` (409) branch passes `undoToken = null` (default) — idempotent re-rate creates no action.

In `SubmitPairRatingRoute.kt`, the `Recorded` branch:

```kotlin
is SubmitPairRatingResult.Recorded ->
    call.respond(
        HttpStatusCode.Created,
        PairRatingResponse(campaignId = result.campaignId.value.toString(), undoToken = result.undoToken),
    )
```

- [ ] **Step 6: Update DTOs**

In `RatingDtos.kt`:

```kotlin
@Serializable
data class RatingResponse(
    val ratingId: String,
    val itemId: String,
    val submittedAs: String,
    val proposedItemId: String? = null,
    val campaignId: String,
    val undoToken: String? = null,
)

@Serializable
data class PairRatingResponse(
    val campaignId: String,
    val undoToken: String? = null,
)
```

- [ ] **Step 7: Update submit use-case tests**

The existing `SubmitRatingUseCaseTest` / `SubmitPairRatingUseCaseTest` now need the three new constructor args (fake `ActionLogRepository`, a `TokenGenerator { "fixed-token" }`, and a pass-through `TransactionManager`). Assert `Accepted.undoToken == "fixed-token"` and that one `SurveyAction` was inserted with the expected recipe (e.g. `createdRatingIds.size == 2` for a text correctif, `createdItemId` set only when fresh).

- [ ] **Step 8: Run + Spotless**

Run: `./gradlew :survey:application:test spotlessCheck`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitPairRatingUseCase.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitRatingRoute.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitPairRatingRoute.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/Submit*UseCaseTest.kt
git commit -s -m "feat(survey): submit use-cases mint undo token + write action recipe in a transaction"
```

### Task 2c.3: Export settling filter (campaign status)

- [ ] **Step 1: Write the failing persistence test**

Add to `PgRatingRepositoryTest`: seed two campaigns — one open, one closed with `closed_at = now - 20s` — each with a rating. `aggregateForExport(since = null)` with the injected cutoff must return **only** the closed-campaign item. Add a second case: a campaign closed `now - 3s` (inside grace) is **excluded**.

> The export method currently takes `since: Instant?`. It needs the clock cutoff. Change the signature to `aggregateForExport(since: Instant?, settledBefore: Instant)` where `settledBefore = clock.now() - CLOSE_GRACE` is computed by the caller (the export worker), keeping the single-clock rule.

- [ ] **Step 2: Update the SQL**

In `PgRatingRepository.kt`, both `AGGREGATE_ALL_SQL` and `AGGREGATE_SINCE_SQL` gain a join + predicate:

```sql
-- add to FROM/WHERE of both aggregate queries:
  JOIN campaigns c ON c.campaign_id = r.campaign_id
 WHERE c.closed_at IS NOT NULL
   AND c.closed_at < ?            -- settledBefore = now - CLOSE_GRACE
   -- (AGGREGATE_SINCE_SQL also keeps its existing: AND r.created_at >= ?)
```

Bind `settledBefore` as the appropriate parameter index (it becomes the first bound param; `since` second when present). Update `aggregateForExport` to bind both. Update the calling export worker to pass `clock.now().minusSeconds(8)`.

> Find the export caller (likely `survey/worker/`) via `grep -rn aggregateForExport survey/`. Update its call site and pass the clock cutoff. If the worker constructs its own clock, reuse the same `Clock` port instance.

- [ ] **Step 3: Run + commit**

Run: `./gradlew :survey:infrastructure:test --tests '*PgRatingRepositoryTest'`
Expected: PASS.

```bash
git add survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgRatingRepository.kt \
        survey/application/src/main/kotlin/com/bliss/survey/application/ports/RatingRepository.kt \
        survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgRatingRepositoryTest.kt \
        survey/worker/  # export caller, if changed
git commit -s -m "feat(survey): settle export by campaign status (closed past 8s grace)"
```

### Task 2c.4: `AnonymizeUserRatingsUseCase` scrubs action user_id

- [ ] **Step 1: Add `ActionLogRepository` to the use case + scrub**

In `AnonymizeUserRatingsUseCase.kt`, add `private val actions: ActionLogRepository` to the constructor and call `actions.scrubUser(userId)` alongside the existing scrubs.

- [ ] **Step 2: Update its test** to pass a fake `ActionLogRepository` and assert `scrubUser` was called.

- [ ] **Step 3: Run + commit**

Run: `./gradlew :survey:application:test --tests '*AnonymizeUserRatingsUseCaseTest'`
Expected: PASS.

```bash
git add survey/application/src/main/kotlin/com/bliss/survey/application/usecases/AnonymizeUserRatingsUseCase.kt \
        survey/application/src/test/kotlin/com/bliss/survey/application/usecases/AnonymizeUserRatingsUseCaseTest.kt
git commit -s -m "feat(survey-application): scrub survey_actions.user_id on RGPD anonymization"
```

### Task 2c.5: The `POST /v1/actions/undo` route

- [ ] **Step 1: Write the failing route test**

`survey/api/src/test/kotlin/com/bliss/survey/api/routes/UndoActionRouteTest.kt`. Mirror the existing route tests' `testApplication` + `Wiring` fake setup. Cases: 204 on Undone, 404 on NotFound, 410 on Expired, and that the token is read from the **body** (`{"token":"..."}`), not the path.

```kotlin
@Test
fun `returns 204 when use case reports Undone`() = testApplication {
    application { module(wiringWith(undoAction = { _, _ -> UndoActionResult.Undone })) }
    val res = client.post("/v1/actions/undo") {
        contentType(ContentType.Application.Json)
        setBody("""{"token":"tok"}""")
    }
    assertThat(res.status).isEqualTo(HttpStatusCode.NoContent)
}
// ... 404 -> NotFound, 410 -> Expired
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :survey:api:test --tests '*UndoActionRouteTest'`
Expected: compile/route-missing failure.

- [ ] **Step 3: Implement the route**

`survey/api/src/main/kotlin/com/bliss/survey/api/routes/UndoActionRoute.kt`:

```kotlin
package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.respondProblem
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.UndoActionRequest
import com.bliss.survey.application.usecases.UndoActionResult
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.undoActionRoute(
    undoAction: suspend (token: String, userId: UserId?) -> UndoActionResult,
) {
    post("/v1/actions/undo") {
        val body = call.receive<UndoActionRequest>()
        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
        when (undoAction(body.token, userId)) {
            UndoActionResult.Undone -> call.respond(HttpStatusCode.NoContent)
            UndoActionResult.NotFound ->
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    ProblemDetails(type = "about:blank", title = "action not found", status = HttpStatusCode.NotFound.value),
                )
            UndoActionResult.Expired ->
                call.respondProblem(
                    HttpStatusCode.Gone,
                    ProblemDetails(type = "about:blank", title = "undo window expired", status = HttpStatusCode.Gone.value),
                )
        }
    }
}
```

Add the `UndoActionRequest` DTO to `RatingDtos.kt` (or a new `ActionDtos.kt`):

```kotlin
@Serializable
data class UndoActionRequest(
    val token: String,
)
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :survey:api:test --tests '*UndoActionRouteTest'`
Expected: PASS.

### Task 2c.6: DI wiring

- [ ] **Step 1: Construct the new collaborators in `Main.kt`**

After the existing repo constructions add:

```kotlin
import java.security.SecureRandom
import java.util.Base64
// ...
val actionLog = PgActionLogRepository(dataSource)
val txManager = PgTransactionManager(dataSource)
val secureRandom = SecureRandom()
val tokens = TokenGenerator {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
```

Pass `actions = actionLog, tokens = tokens, tx = txManager` into the `SubmitRatingUseCase` and `SubmitPairRatingUseCase` constructors. Add `actions = actionLog` to `AnonymizeUserRatingsUseCase`. Construct:

```kotlin
val undoAction =
    UndoActionUseCase(
        actions = actionLog,
        ratings = ratings,
        pairRatings = pairRatings,
        items = items,
        proposedBy = proposedBy,
        progress = progress,
        campaigns = campaignRepository,
        tx = txManager,
        clock = clock,
    )
```

- [ ] **Step 2: Add `undoAction` to the `Wiring` data class and register the route**

Add to `Wiring`:

```kotlin
val undoAction: suspend (String, UserId?) -> UndoActionResult,
```

Set it in the `Wiring(...)` literal: `undoAction = { token, uid -> undoAction.execute(token, uid) }`. In `Module.kt`, where the other routes are installed (e.g. `submitRatingRoute(...)`), add `undoActionRoute(wiring.undoAction)`.

> Open `Module.kt` and place `undoActionRoute(wiring.undoAction)` next to the existing `routing { ... }` route registrations — mirror exactly how `submitRatingRoute` / `submitPairRatingRoute` are wired (same `Route` receiver, same `wiring.` field access).

- [ ] **Step 3: Build the whole survey context**

Run: `./gradlew :survey:api:build`
Expected: BUILD SUCCESSFUL (compiles wiring, runs route tests).

- [ ] **Step 4: Commit**

```bash
git add survey/api/src/main/kotlin/com/bliss/survey/api/routes/UndoActionRoute.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/dto/*.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt \
        survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt \
        survey/api/src/test/kotlin/com/bliss/survey/api/routes/UndoActionRouteTest.kt
git commit -s -m "feat(survey-api): wire POST /v1/actions/undo route + undo use case"
```

### Task 2c.7: (moved to Phase 0.5)

> ADR-0059 amendment and `docs/adr/INDEX.md` update shipped in Phase 0.5, which merges before Phase 1. Nothing to do here — verify the amendment is on `main` before starting this phase.

---

## Phase 5 — Frontend PR (PR 3)

**Goal:** Surface a durable, single-level undo as a persistent on-card `Annuler` control on both `/sondage` (binary) and `/sondage/pairs` routes. After a rating submit returns an `undoToken`, the route stashes the just-rated item/pair; the control POSTs the token to `/v1/actions/undo` and, on success, re-presents the stashed item/pair so the user can re-rate.

**Files:**
- Regenerate: `frontend/src/infrastructure/api/survey/types.ts` (via `pnpm api:generate` — do NOT hand-edit)
- Modify: `frontend/src/application/survey/types.ts`
- Modify: `frontend/src/infrastructure/api/survey/client.ts`
- Modify: `frontend/src/infrastructure/session/localStorageSurveyAnon.ts`
- Create: `frontend/src/ui/components/sondage/UndoBar.tsx`
- Modify: `frontend/src/ui/components/sondage/index.ts`
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx`
- Modify: `frontend/src/ui/routes/sondage.pairs.lazy.tsx`
- Test: `frontend/tests/http-survey-client.test.ts`
- Test: `frontend/tests/sondage-route.test.tsx`
- Test: `frontend/tests/sondage-pairs-route.test.tsx`

> **Prerequisite:** PR 1 (schema) must be merged to `main` before this phase, because `pnpm api:generate` reads the merged `survey/api/openapi.yaml`. If the schema is still on a branch, generate against that branch's spec, but the `regen-and-diff` CI gate only passes once the spec is on `main`.

> **Frontend conventions (binding — see `.claude/skills/frontend`):** uncontrolled inputs (ADR-0002 §4); never hand-edit generated `types.ts`; adapters reach `ui` only via TanStack Router context; one-line comments only; Panda tokens not hex literals; tests under `frontend/tests/`, Vitest globals OFF (import `describe/it/expect/vi`), wrap state mutations in `act(...)`, focus-then-click helper for jsdom; hardcode fixture timestamps (no `new Date()` / `Math.random()`); destructure stable functions out of hook return objects before using them in deps.

### Task 3.1: Regenerate OpenAPI types

- [ ] **Step 1: Regenerate against the merged spec**

Run: `cd frontend && pnpm install --frozen-lockfile && pnpm api:generate`
Expected: `src/infrastructure/api/survey/types.ts` now contains `undoToken?: string | null` on `RatingResponse` and `PairRatingResponse`, plus the `UndoActionRequest` schema and the `/v1/actions/undo` path entry.

- [ ] **Step 2: Confirm no drift**

Run: `cd frontend && pnpm api:check`
Expected: exits 0 (generated file matches the spec). This mirrors the `regen-and-diff` CI gate exactly.

- [ ] **Step 3: Commit the regenerated types**

```bash
git add frontend/src/infrastructure/api/survey/types.ts
git commit -s -m "chore(api-survey): regenerate openapi types for undoToken + undo path"
```

### Task 3.2: Extend the application port

**Files:**
- Modify: `frontend/src/application/survey/types.ts`

- [ ] **Step 1: Add `undoToken` to `RatingResult` and add a pair result type**

In `frontend/src/application/survey/types.ts`, change the `RatingResult` interface to carry the token, and add a `PairRatingResult`:

```ts
export interface RatingResult {
  readonly ratingId: string;
  readonly itemId: string;
  readonly submittedAs: SubmittedAs;
  readonly proposedItemId: string | null;
  readonly undoToken: string | null;
}

export interface PairRatingResult {
  readonly undoToken: string | null;
}
```

- [ ] **Step 2: Change `submitPairRating`'s return and add `undoAction` to the port**

In the `SurveyClient` interface, change `submitPairRating` to return the new result and add `undoAction`:

```ts
  submitPairRating(body: PairRatingSubmission): Promise<PairRatingResult>;
  undoAction(token: string): Promise<void>;
```

- [ ] **Step 3: Add `remove` to the anon-dedup port**

So an undone anon rating is offered again. Change the `SurveyAnonStore` interface:

```ts
export interface SurveyAnonStore {
  list(): ReadonlyArray<string>;
  add(itemId: string): void;
  remove(itemId: string): void;
}
```

- [ ] **Step 4: Typecheck (expected to fail until adapters catch up)**

Run: `cd frontend && pnpm typecheck`
Expected: FAIL — `client.ts`, `localStorageSurveyAnon.ts`, and the two routes don't yet satisfy the new port. That's the red state for the next tasks. Do not commit yet.

### Task 3.3: HTTP adapter — token passthrough, `undoAction`, error classes

**Files:**
- Modify: `frontend/src/infrastructure/api/survey/client.ts`

- [ ] **Step 1: Add the two undo error classes**

After `NoCampaignError` in `client.ts`:

```ts
export class UndoExpiredError extends Error {
  constructor() {
    super('undo window expired');
    this.name = 'UndoExpiredError';
  }
}

export class UndoUnavailableError extends Error {
  constructor() {
    super('undo unavailable');
    this.name = 'UndoUnavailableError';
  }
}
```

- [ ] **Step 2: Import the new application types**

Add `PairRatingResult` to the type import from `@/application/survey` at the top of `client.ts`.

- [ ] **Step 3: Pass `undoToken` through on the binary success path**

The binary `submitRating` already returns `(await res.json()) as RatingResult`; with the regenerated envelope carrying `undoToken`, no body change is needed there. But the `409 AlreadyRatedError` branch constructs nothing — it parses the existing rating, which now also carries `undoToken` from the server. Leave it as-is (the cast picks up the new field).

- [ ] **Step 4: Return a result from `submitPairRating`**

Replace the `submitPairRating` body so it returns `PairRatingResult` and surfaces the token; keep the existing 401/409/423 handling:

```ts
  const submitPairRating: SurveyClient['submitPairRating'] = async (body: PairRatingSubmission) => {
    const res = await fetchImpl(`${base}/v1/ratings/pair`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    // 409 means the auth caller already rated this pair; surface as AlreadyRatedError without a payload body.
    if (res.status === 409) {
      throw new AlreadyRatedError({
        ratingId: '',
        itemId: body.leftItemId,
        submittedAs: 'auth',
        proposedItemId: null,
        undoToken: null,
      });
    }
    if (res.status === 423) throw new SondageLockedError();
    if (!res.ok) throw new Error(`submitPairRating failed: ${res.status}`);
    const json = (await res.json()) as { undoToken?: string | null };
    return { undoToken: json.undoToken ?? null } satisfies PairRatingResult;
  };
```

- [ ] **Step 5: Implement `undoAction`**

Add before the `return { ... }` block:

```ts
  const undoAction: SurveyClient['undoAction'] = async (token: string) => {
    const res = await fetchImpl(`${base}/v1/actions/undo`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (res.status === 204) return;
    if (res.status === 404) throw new UndoUnavailableError();
    if (res.status === 410) throw new UndoExpiredError();
    throw new Error(`undoAction failed: ${res.status}`);
  };
```

- [ ] **Step 6: Add `undoAction` to the returned object**

Add `undoAction,` to the object literal returned from `createHttpSurveyClient`.

- [ ] **Step 7: Export the new error classes from the infra barrel**

In `frontend/src/infrastructure/index.ts` (the barrel re-exporting the survey client errors — mirror how `SignInRequiredError` / `NoCampaignError` are already re-exported), add `UndoExpiredError` and `UndoUnavailableError`.

> Confirm the barrel path first: `grep -rn "NoCampaignError" frontend/src/infrastructure/index.ts`. Add the two new names to the same `export { ... } from './api/survey/client'` line.

### Task 3.4: anon-store `remove`

**Files:**
- Modify: `frontend/src/infrastructure/session/localStorageSurveyAnon.ts`

- [ ] **Step 1: Add `remove` to the interface and the implementation**

Add to `SurveyAnonRatedStore`:

```ts
  remove(itemId: string): void;
```

And to `surveyAnonRatedStore`, after `add`:

```ts
  remove(itemId: string): void {
    const current = readSafe();
    if (!current.includes(itemId)) return;
    writeSafe(current.filter((id) => id !== itemId));
  },
```

### Task 3.5: `UndoBar` component

**Files:**
- Create: `frontend/src/ui/components/sondage/UndoBar.tsx`
- Modify: `frontend/src/ui/components/sondage/index.ts`

- [ ] **Step 1: Write the component**

Create `frontend/src/ui/components/sondage/UndoBar.tsx`:

```tsx
import { css } from 'styled-system/css';

const barStyles = css({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 'sm',
  bg: 'surfaceMuted',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  paddingInline: 'md',
  paddingBlock: 'sm',
});

const labelStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

const buttonStyles = css({
  minHeight: '44px',
  paddingInline: 'md',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'accent',
  bg: 'surface',
  border: '1px solid token(colors.accent)',
  borderRadius: 'sm',
  cursor: 'pointer',
  _hover: { bg: 'surfaceMuted' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export interface UndoBarProps {
  readonly onUndo: () => void;
  readonly busy?: boolean;
}

export function UndoBar({ onUndo, busy = false }: UndoBarProps) {
  return (
    <div className={barStyles} data-testid="undo-bar">
      <p className={labelStyles}>Action enregistrée.</p>
      <button
        type="button"
        className={buttonStyles}
        onClick={onUndo}
        disabled={busy}
        data-testid="undo-button"
      >
        Annuler
      </button>
    </div>
  );
}
```

- [ ] **Step 2: Export it from the barrel**

Add to `frontend/src/ui/components/sondage/index.ts`:

```ts
export { UndoBar } from './UndoBar';
```

### Task 3.6: Wire undo into the binary route

**Files:**
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx`
- Test: `frontend/tests/sondage-route.test.tsx`

- [ ] **Step 1: Write the failing test**

In `frontend/tests/sondage-route.test.tsx`, the `stubSurveyClient` already returns `ratingResult`; first add `undoToken` to that fixture (and a default `undoAction` mock). At the top, change the `ratingResult` fixture:

```ts
const ratingResult: RatingResult = {
  ratingId: '0190e3a4-7a2c-7c9e-8f1a-1234567890ab',
  itemId: sampleItem.itemId,
  submittedAs: 'anon',
  proposedItemId: null,
  undoToken: 'tok_sample_123',
};
```

Add `undoAction: vi.fn().mockResolvedValue(undefined),` and `remove` to the stub's anon usage as needed. Then add a test:

```ts
it('shows Annuler after a verdict and re-presents the item on undo', async () => {
  const undoAction = vi.fn().mockResolvedValue(undefined);
  const getNextItem = vi
    .fn()
    .mockResolvedValueOnce(sampleItem)
    .mockResolvedValueOnce(null);
  const surveyClient = stubSurveyClient({ getNextItem, undoAction });
  renderSondage({ surveyClient });

  const good = await screen.findByRole('button', { name: /Bonne définition/ });
  const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };
  await act(async () => { click(good); });

  const undo = await screen.findByTestId('undo-button');
  await act(async () => { click(undo); });

  expect(undoAction).toHaveBeenCalledWith('tok_sample_123');
  // The stashed item is re-presented for re-rating.
  expect(await screen.findByRole('button', { name: /Bonne définition/ })).toBeTruthy();
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && pnpm test --run tests/sondage-route.test.tsx -t 'shows Annuler'`
Expected: FAIL — no `undo-button` rendered.

- [ ] **Step 3: Implement undo state + handler + render**

In `sondage.lazy.tsx`:

(a) Add the import: `import { LockBanner, RatingCard, SignInBanner, UndoBar, useCampaignStatus } from '@/ui/components/sondage';`

(b) Add undo state near the other `useState`s:

```tsx
  const [lastAction, setLastAction] = useState<{ token: string; item: SurveyItem } | null>(null);
  const [undoBusy, setUndoBusy] = useState(false);
```

(c) In `onVerdict`, after a successful `submitRating` (the `await loadNext()` success path), record the action. Replace the success block:

```tsx
      const result = await surveyClient.submitRating(currentItem.itemId, payload);
      analytics.trackEvent(
        'survey',
        'verdict_submitted',
        `tier=${currentItem.tier};verdict=${verdict}`,
      );
      if (!isAuth) surveyAnonStore?.add(currentItem.itemId);
      if (result.undoToken) setLastAction({ token: result.undoToken, item: currentItem });
      await loadNext();
```

(d) In the `SKIP` branch and in `onCorriger`'s success path, clear stale undo state. In the `SKIP` branch add `setLastAction(null);` before `await loadNext();`. In `onCorriger` success, after the `submitRating` call, set it:

```tsx
      const result = await surveyClient.submitRating(currentItem.itemId, payload);
      analytics.trackEvent('survey', 'correctif_proposed', `tier=${currentItem.tier}`);
      if (result.undoToken) setLastAction({ token: result.undoToken, item: currentItem });
      await loadNext();
```

(e) Add the undo handler:

```tsx
  async function onUndo(): Promise<void> {
    if (!surveyClient || !lastAction) return;
    const { token, item: stashedItem } = lastAction;
    setUndoBusy(true);
    try {
      await surveyClient.undoAction(token);
      analytics.trackEvent('survey', 'verdict_undone', undefined);
      if (!isAuth) surveyAnonStore?.remove(stashedItem.itemId);
      authSkippedIdsRef.current.delete(stashedItem.itemId);
      setLastAction(null);
      setError(null);
      setItem(stashedItem);
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      setLastAction(null);
      if (name === 'UndoExpiredError') {
        setError('Trop tard pour annuler : la campagne est terminée.');
      } else if (name === 'UndoUnavailableError') {
        setError('Cette action ne peut plus être annulée.');
      } else {
        setError(messageForApiError(cause));
      }
    } finally {
      setUndoBusy(false);
    }
  }
```

(f) Render `UndoBar` in the JSX, after the `RatingCard` block (so it sits below the current card):

```tsx
        {lastAction !== null ? <UndoBar onUndo={onUndo} busy={undoBusy} /> : null}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && pnpm test --run tests/sondage-route.test.tsx`
Expected: PASS (all binary-route tests, including the new undo test).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/application/survey/types.ts \
        frontend/src/infrastructure/api/survey/client.ts \
        frontend/src/infrastructure/session/localStorageSurveyAnon.ts \
        frontend/src/infrastructure/index.ts \
        frontend/src/ui/components/sondage/UndoBar.tsx \
        frontend/src/ui/components/sondage/index.ts \
        frontend/src/ui/routes/sondage.lazy.tsx \
        frontend/tests/sondage-route.test.tsx
git commit -s -m "feat(survey-frontend): on-card undo for binary ratings"
```

### Task 3.7: Wire undo into the pairs route

**Files:**
- Modify: `frontend/src/ui/routes/sondage.pairs.lazy.tsx`
- Test: `frontend/tests/sondage-pairs-route.test.tsx`

- [ ] **Step 1: Write the failing test**

In `frontend/tests/sondage-pairs-route.test.tsx`, make the stub's `submitPairRating` resolve a token and add an `undoAction` mock, then add a test mirroring the binary one but asserting the pair is re-presented. The pair stub's `submitPairRating` should resolve `{ undoToken: 'tok_pair_1' }`:

```ts
it('shows Annuler after a pair verdict and re-presents the pair on undo', async () => {
  const undoAction = vi.fn().mockResolvedValue(undefined);
  const getNextPair = vi
    .fn()
    .mockResolvedValueOnce(samplePair)
    .mockResolvedValueOnce(null);
  const surveyClient = stubSurveyClient({
    getNextPair,
    submitPairRating: vi.fn().mockResolvedValue({ undoToken: 'tok_pair_1' }),
    undoAction,
  });
  renderSondagePairs({ surveyClient });

  const bothGood = await screen.findByRole('button', { name: /toutes les deux bonnes/i });
  const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };
  await act(async () => { click(bothGood); });

  const undo = await screen.findByTestId('undo-button');
  await act(async () => { click(undo); });

  expect(undoAction).toHaveBeenCalledWith('tok_pair_1');
  expect(await screen.findByRole('button', { name: /toutes les deux bonnes/i })).toBeTruthy();
});
```

> Adjust the button accessible-name regexes to match `PairCard`'s actual `aria-label`s — grep `PairCard.tsx` for the verdict button labels and use those. Use the existing `samplePair` / `renderSondagePairs` helpers already in the file (confirm their names with a quick read of the test's top).

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && pnpm test --run tests/sondage-pairs-route.test.tsx -t 'shows Annuler'`
Expected: FAIL — no `undo-button`.

- [ ] **Step 3: Implement undo in the pairs route**

In `sondage.pairs.lazy.tsx`:

(a) Import `UndoBar`: `import { LockBanner, PairCard, SignInBanner, UndoBar, useCampaignStatus } from '@/ui/components/sondage';`

(b) Add state:

```tsx
  const [lastAction, setLastAction] = useState<{ token: string; pair: ItemPair } | null>(null);
  const [undoBusy, setUndoBusy] = useState(false);
```

(c) In `onVerdict`, the `SKIP` branch adds `setLastAction(null);` before `await loadNext();`. The success path captures the token:

```tsx
      const result = await surveyClient.submitPairRating(payload);
      analytics.trackEvent(
        'survey',
        'pair_verdict_submitted',
        `tier=${currentPair.left.tier};verdict=${verdict}`,
      );
      if (!isAuth) {
        surveyAnonStore?.add(leftItemId);
        surveyAnonStore?.add(rightItemId);
      }
      if (result.undoToken) setLastAction({ token: result.undoToken, pair: currentPair });
      await loadNext();
```

(d) Add the undo handler (declare it with `useCallback` to match this file's style, or a plain async function — the route already mixes both; a plain function is fine since it isn't in a dep array):

```tsx
  async function onUndo(): Promise<void> {
    if (!surveyClient || !lastAction) return;
    const { token, pair: stashedPair } = lastAction;
    setUndoBusy(true);
    try {
      await surveyClient.undoAction(token);
      analytics.trackEvent('survey', 'pair_verdict_undone', undefined);
      if (!isAuth) {
        surveyAnonStore?.remove(stashedPair.left.itemId);
        surveyAnonStore?.remove(stashedPair.right.itemId);
      }
      authSkippedIdsRef.current.delete(stashedPair.left.itemId);
      authSkippedIdsRef.current.delete(stashedPair.right.itemId);
      setLastAction(null);
      setError(null);
      setPair(stashedPair);
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      setLastAction(null);
      if (name === 'UndoExpiredError') {
        setError('Trop tard pour annuler : la campagne est terminée.');
      } else if (name === 'UndoUnavailableError') {
        setError('Cette action ne peut plus être annulée.');
      } else {
        setError(messageForApiError(cause));
      }
    } finally {
      setUndoBusy(false);
    }
  }
```

(e) Render after the `PairCard` block:

```tsx
        {lastAction !== null ? <UndoBar onUndo={onUndo} busy={undoBusy} /> : null}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && pnpm test --run tests/sondage-pairs-route.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/routes/sondage.pairs.lazy.tsx \
        frontend/tests/sondage-pairs-route.test.tsx
git commit -s -m "feat(survey-frontend): on-card undo for pair ratings"
```

### Task 3.8: HTTP-client unit tests for `undoAction` + pair token

**Files:**
- Test: `frontend/tests/http-survey-client.test.ts`

- [ ] **Step 1: Write the tests**

Add a `describe` block to `frontend/tests/http-survey-client.test.ts`:

```ts
describe('HttpSurveyClient.undoAction', () => {
  it('resolves on 204', async () => {
    let body: unknown = null;
    server.use(
      http.post(`${BASE}/v1/actions/undo`, async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await expect(client.undoAction('tok_abc')).resolves.toBeUndefined();
    expect(body).toEqual({ token: 'tok_abc' });
  });

  it('throws UndoUnavailableError on 404', async () => {
    server.use(
      http.post(`${BASE}/v1/actions/undo`, () => new HttpResponse(null, { status: 404 })),
    );
    await expect(client.undoAction('tok_abc')).rejects.toMatchObject({
      name: 'UndoUnavailableError',
    });
  });

  it('throws UndoExpiredError on 410', async () => {
    server.use(
      http.post(`${BASE}/v1/actions/undo`, () => new HttpResponse(null, { status: 410 })),
    );
    await expect(client.undoAction('tok_abc')).rejects.toMatchObject({
      name: 'UndoExpiredError',
    });
  });
});

describe('HttpSurveyClient.submitPairRating', () => {
  it('returns the undoToken from the response envelope', async () => {
    server.use(
      http.post(`${BASE}/v1/ratings/pair`, () =>
        HttpResponse.json({ undoToken: 'tok_pair' }),
      ),
    );
    const result = await client.submitPairRating({
      leftItemId: itemId,
      rightItemId: itemId,
      verdict: 'BOTH_GOOD',
      difficulte: 3,
      latencyMs: 100,
    });
    expect(result).toEqual({ undoToken: 'tok_pair' });
  });
});
```

> The test file imports error classes from `@/infrastructure`; `UndoExpiredError`/`UndoUnavailableError` are asserted by `name` so no extra import is strictly required, but add them to the import if you assert via `instanceof`.

- [ ] **Step 2: Run them**

Run: `cd frontend && pnpm test --run tests/http-survey-client.test.ts`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/tests/http-survey-client.test.ts
git commit -s -m "test(survey-frontend): cover undoAction + pair undoToken in http client"
```

### Task 3.9: Full frontend validation

- [ ] **Step 1: Run the full gate locally (mirrors CI)**

Run:
```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm test
pnpm build
```
Expected: all green. `pnpm build` then verify MSW is still tree-shaken from prod (`grep -r setupWorker dist/` → empty) only if you touched mock paths (you didn't, so this is a sanity check).

- [ ] **Step 2: Manual smoke (golden path + edge)**

Run `pnpm dev`, open `/sondage`, rate an item, confirm `Annuler` appears, click it, confirm the same item returns and is re-rateable. Repeat on `/sondage/pairs`. Edge: rate, wait, then simulate a 410 (campaign closed) — confirm the error copy renders and the bar clears. If you cannot exercise the 410 path in dev, say so explicitly in the PR body rather than claiming it works.

- [ ] **Step 3: Open the PR**

```bash
git push -u origin feat/survey-frontend-undo
gh pr create --base main \
  --title "feat(survey-frontend): on-card undo for sondage ratings" \
  --body "$(cat <<'EOF'
## Summary
- Persistent on-card `Annuler` control on `/sondage` and `/sondage/pairs`.
- `SurveyClient.undoAction(token)` → `POST /v1/actions/undo`; 204/404/410 mapped to success / `UndoUnavailableError` / `UndoExpiredError`.
- Submit results now carry `undoToken`; routes stash-and-restore the rated item/pair on undo.

## Context
- Bounded context: survey / frontend (ui + infrastructure + application).
- Schema shipped first in PR 1; backend in PRs 2a/2b/2c.
- Spec: `docs/superpowers/specs/2026-05-30-survey-undo-design.md`. ADR-0056, ADR-0059.

## Test plan
- [ ] `pnpm typecheck` `pnpm lint` `pnpm test` `pnpm build` green.
- [ ] New: undo re-presents the item (binary) and pair, calls `undoAction` with the token.
- [ ] New: http-client maps 204/404/410; `submitPairRating` returns the token.
- [ ] Manual: rate → Annuler → re-rate, on both routes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
