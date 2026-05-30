# Identity User Roles + `UserRoleChanged` Event — Implementation Plan (Spec A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable `role` authz primitive to identity users plus a fire-and-forget `UserRoleChanged` NATS event, assigned via a configure-in-cluster bootstrap Job.

**Architecture:** Hexagonal, mirroring the existing identity context. `Role` lives in `domain/`; `SetUserRoleUseCase` + `UserRoleChangedBroadcaster` port in `application/`; the Postgres column, repo methods, and NATS adapter in `infrastructure/`; the `main()` arg-dispatch entrypoint, AsyncAPI event fragment, and Helm bootstrap Job in `api/`. The role is assigned only by the bootstrap Job (no HTTP mutation surface).

**Tech Stack:** Kotlin 2.x + Ktor on JDK 21, Postgres via Flyway, NATS JetStream (ADR-0049), Helm, Testcontainers + assertk + JUnit5.

**Spec:** `docs/superpowers/specs/2026-05-30-identity-user-roles-design.md`

**Companion ADR:** ADR-0060 (next free number; highest existing is 0059).

---

## PR sequencing (each under the 400-line cap; see [[feedback-standing-cap-override]])

This Spec A ships as **three stacked PRs**. Each compiles, tests, and is independently reviewable.

| Phase | Branch | Base | PR title prefix | Scope (tasks) |
|---|---|---|---|---|
| A1 | `docs/identity-roles-adr-event` | `main` | `docs(identity):` | ADR-0060 + `UserRoleChanged.yaml` event contract + `INDEX.md` (Tasks 11–12) — schema-first, lands first per ADR-0001 §3 |
| A2 | `feat/identity-role-persistence` | `main` | `feat(identity-*):` | `Role`, `User.role`, migration, repo read/write + `updateRole`, ports (Tasks 1–6) |
| A3 | `feat/identity-role-bootstrap` | A2 | `feat(identity-*):` | `SetUserRoleUseCase`, NATS adapter, `main()` dispatch, Helm Job + values (Tasks 7–10) |

If any phase exceeds 400 lines net of generated/blank lines, invoke the cap-override in the PR body with the justification "cohesive role primitive; splitting further fragments tightly-coupled domain+persistence". The spec doc itself is already committed on `docs/identity-user-roles`.

---

## File Structure

**Create:**
- `identity/domain/src/main/kotlin/com/bliss/identity/domain/user/Role.kt` — the enum + wire mapping.
- `identity/domain/src/test/kotlin/com/bliss/identity/domain/user/RoleTest.kt`
- `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRoleChangedBroadcaster.kt`
- `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCase.kt`
- `identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/FakeUserRepository.kt`
- `identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/RecordingUserRoleChangedBroadcaster.kt`
- `identity/application/src/test/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCaseTest.kt`
- `identity/infrastructure/src/main/resources/db/migration/V5__user_role.sql`
- `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/NatsUserRoleChangedBroadcaster.kt`
- `identity/api/events/UserRoleChanged.yaml`
- `identity/api/deploy/chart/templates/job-maintainer-roles.yaml`
- `identity/api/src/test/kotlin/com/bliss/identity/api/MaintainerRoleBootstrapTest.kt`
- `docs/adr/0060-identity-user-roles.md`

**Modify:**
- `identity/domain/src/main/kotlin/com/bliss/identity/domain/user/User.kt` — add `role` field (defaulted).
- `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRepository.kt` — add `updateRole`.
- `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/InMemoryUserRepository.kt` — impl `updateRole`.
- `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepository.kt` — read/write `role`, impl `updateRole`.
- `identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepositoryTest.kt` — add role round-trip tests (create the file if absent; see Task 6).
- `identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt` — arg dispatch + `setMaintainerRoles` helper.
- `identity/api/deploy/chart/values.yaml` and `values-prod.yaml` — `maintainerRoleBootstrap` block.
- `docs/adr/INDEX.md` — register ADR-0060.

---

## Task 1: `Role` domain enum

**Files:**
- Create: `identity/domain/src/main/kotlin/com/bliss/identity/domain/user/Role.kt`
- Test: `identity/domain/src/test/kotlin/com/bliss/identity/domain/user/RoleTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.identity.domain.user

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertFailure
import org.junit.jupiter.api.Test

class RoleTest {
    @Test
    fun `wire values are stable lowercase strings`() {
        assertThat(Role.PLAYER.wire).isEqualTo("player")
        assertThat(Role.MAINTAINER.wire).isEqualTo("maintainer")
    }

    @Test
    fun `fromWire round-trips every role`() {
        Role.entries.forEach { assertThat(Role.fromWire(it.wire)).isEqualTo(it) }
    }

    @Test
    fun `fromWire rejects unknown`() {
        assertFailure { Role.fromWire("admin") }.isInstanceOf(IllegalArgumentException::class)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :identity:domain:test --tests "com.bliss.identity.domain.user.RoleTest"`
Expected: FAIL — `Role` is unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.bliss.identity.domain.user

/** Authz primitive (ADR-0060). `wire` is the stable DB + event-payload spelling. */
enum class Role(val wire: String) {
    PLAYER("player"),
    MAINTAINER("maintainer"),
    ;

    companion object {
        fun fromWire(raw: String): Role =
            entries.firstOrNull { it.wire == raw }
                ?: throw IllegalArgumentException("Unknown role: $raw")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :identity:domain:test --tests "com.bliss.identity.domain.user.RoleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add identity/domain/src/main/kotlin/com/bliss/identity/domain/user/Role.kt \
        identity/domain/src/test/kotlin/com/bliss/identity/domain/user/RoleTest.kt
git commit -s -m "feat(identity-domain): add Role authz primitive"
```

---

## Task 2: `User` gains a defaulted `role` field

**Files:**
- Modify: `identity/domain/src/main/kotlin/com/bliss/identity/domain/user/User.kt`
- Test: `identity/domain/src/test/kotlin/com/bliss/identity/domain/user/RoleTest.kt` (extend)

The field is added **last with a default** so existing construction sites (e.g. `CompleteOidcLoginUseCase.kt:109`) compile unchanged and new players default to `PLAYER`.

- [ ] **Step 1: Add the failing test** (append to `RoleTest.kt`)

```kotlin
    @Test
    fun `new User defaults to PLAYER`() {
        val u = User(
            id = UserId(java.util.UUID.randomUUID()),
            displayName = DisplayName.of("Alice"),
            createdAt = java.time.Instant.parse("2026-05-30T00:00:00Z"),
            lastSeenAt = java.time.Instant.parse("2026-05-30T00:00:00Z"),
        )
        assertThat(u.role).isEqualTo(Role.PLAYER)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :identity:domain:test --tests "com.bliss.identity.domain.user.RoleTest"`
Expected: FAIL — `User` has no `role` property.

- [ ] **Step 3: Modify `User.kt`**

```kotlin
package com.bliss.identity.domain.user

import java.time.Instant

data class User(
    val id: UserId,
    val displayName: DisplayName,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val role: Role = Role.PLAYER,
)
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :identity:domain:test --tests "com.bliss.identity.domain.user.RoleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add identity/domain/src/main/kotlin/com/bliss/identity/domain/user/User.kt \
        identity/domain/src/test/kotlin/com/bliss/identity/domain/user/RoleTest.kt
git commit -s -m "feat(identity-domain): User carries a role, defaulting to player"
```

---

## Task 3: `UserRepository.updateRole` port + in-memory adapter

**Files:**
- Modify: `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRepository.kt`
- Modify: `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/InMemoryUserRepository.kt`
- Test: `identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/persistence/InMemoryUserRepositoryTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `InMemoryUserRepositoryTest.kt`:

```kotlin
package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryUserRepositoryTest {
    private val now = Instant.parse("2026-05-30T00:00:00Z")

    private fun user(id: UUID = UUID.randomUUID()) =
        User(UserId(id), DisplayName.of("Alice"), now, now)

    @Test
    fun `updateRole changes the stored role`() = runTest {
        val repo = InMemoryUserRepository()
        val u = user()
        repo.create(u)
        repo.updateRole(u.id, Role.MAINTAINER)
        assertThat(repo.findById(u.id)?.role).isEqualTo(Role.MAINTAINER)
    }

    @Test
    fun `updateRole is a no-op for an unknown user`() = runTest {
        val repo = InMemoryUserRepository()
        repo.updateRole(UserId(UUID.randomUUID()), Role.MAINTAINER) // must not throw
        assertThat(repo.findById(UserId(UUID.randomUUID()))).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :identity:infrastructure:test --tests "*InMemoryUserRepositoryTest"`
Expected: FAIL — `updateRole` unresolved.

- [ ] **Step 3: Add to the port** (`UserRepository.kt`), after `updateDisplayName`:

```kotlin
    /** No-op if [id] does not exist. */
    suspend fun updateRole(
        id: UserId,
        role: Role,
    )
```

Add the import `import com.bliss.identity.domain.user.Role` at the top.

- [ ] **Step 4: Implement in `InMemoryUserRepository.kt`**

Add the import `import com.bliss.identity.domain.user.Role`, then:

```kotlin
    override suspend fun updateRole(
        id: UserId,
        role: Role,
    ) {
        byId.computeIfPresent(id) { _, existing -> existing.copy(role = role) }
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :identity:infrastructure:test --tests "*InMemoryUserRepositoryTest"`
Expected: PASS. (`PostgresUserRepository` will not yet compile against the new port method — that is Task 5; if the module fails to compile, run only after Task 5. To keep this task green standalone, add the Postgres `updateRole` stub now as part of Step 4 — see Task 5 Step 3 for the final body.)

- [ ] **Step 6: Commit**

```bash
git add identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRepository.kt \
        identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/InMemoryUserRepository.kt \
        identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/persistence/InMemoryUserRepositoryTest.kt
git commit -s -m "feat(identity): add UserRepository.updateRole port + in-memory adapter"
```

---

## Task 4: Migration `V5__user_role.sql`

**Files:**
- Create: `identity/infrastructure/src/main/resources/db/migration/V5__user_role.sql`

- [ ] **Step 1: Write the migration**

```sql
-- authz primitive; DEFAULT 'player' makes this column additive (no backfill needed).
ALTER TABLE identity_users
    ADD COLUMN role TEXT NOT NULL DEFAULT 'player'
        CHECK (role IN ('player', 'maintainer'));
```

- [ ] **Step 2: Commit** (verified by the Postgres test in Task 5)

```bash
git add identity/infrastructure/src/main/resources/db/migration/V5__user_role.sql
git commit -s -m "feat(identity-infrastructure): migration adds identity_users.role"
```

---

## Task 5: `PostgresUserRepository` reads/writes `role` + `updateRole`

**Files:**
- Modify: `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepository.kt`
- Test: `identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepositoryTest.kt` (create)

- [ ] **Step 1: Write the failing test** (Testcontainers + Flyway, mirroring `PostgresUserProviderRepositoryTest`)

```kotlin
package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresUserRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PostgresUserRepository
    private val now = Instant.parse("2026-05-30T12:00:00Z")

    private fun user(id: UUID = UUID.randomUUID(), role: Role = Role.PLAYER) =
        User(UserId(id), DisplayName.of("Alice"), now, now, role)

    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl; username = pg.username; password = pg.password
            },
        )
        Flyway.configure().dataSource(dataSource).load().migrate()
        repo = PostgresUserRepository(dataSource)
    }

    @AfterEach
    fun clean() {
        dataSource.connection.use { it.createStatement().use { s -> s.execute("DELETE FROM identity_users") } }
    }

    @AfterAll
    fun stop() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `new user defaults to player on read`() = runTest {
        val u = user()
        repo.create(u)
        assertThat(repo.findById(u.id)?.role).isEqualTo(Role.PLAYER)
    }

    @Test
    fun `create persists an explicit role`() = runTest {
        val u = user(role = Role.MAINTAINER)
        repo.create(u)
        assertThat(repo.findById(u.id)?.role).isEqualTo(Role.MAINTAINER)
    }

    @Test
    fun `updateRole promotes an existing user`() = runTest {
        val u = user()
        repo.create(u)
        repo.updateRole(u.id, Role.MAINTAINER)
        assertThat(repo.findById(u.id)?.role).isEqualTo(Role.MAINTAINER)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :identity:infrastructure:test --tests "*PostgresUserRepositoryTest"`
Expected: FAIL — `create`/`findById` do not handle `role`; `updateRole` unresolved.

- [ ] **Step 3: Modify `PostgresUserRepository.kt`**

Add import `import com.bliss.identity.domain.user.Role`. Update the SQL constants and methods:

```kotlin
    override suspend fun updateRole(
        id: UserId,
        role: Role,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_ROLE_SQL).use { stmt ->
                    stmt.setString(1, role.wire)
                    stmt.setObject(2, id.value)
                    stmt.executeUpdate()
                }
            }
        }
```

In `create`, extend the INSERT to set `role`:

```kotlin
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    stmt.setObject(1, user.id.value)
                    stmt.setString(2, user.displayName.value)
                    stmt.setObject(3, user.createdAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.setObject(4, user.lastSeenAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    stmt.setString(5, user.role.wire)
                    stmt.executeUpdate()
                }
```

In `toUser`, read the column:

```kotlin
    private fun ResultSet.toUser(): User =
        User(
            id = UserId(getObject("user_id", UUID::class.java)),
            displayName = DisplayName.of(getString("display_name")),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            lastSeenAt = getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
            role = Role.fromWire(getString("role")),
        )
```

Update the SQL constants:

```kotlin
        private const val INSERT_SQL =
            "INSERT INTO identity_users (user_id, display_name, created_at, last_seen_at, role) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING"
        private const val SELECT_SQL =
            "SELECT user_id, display_name, created_at, last_seen_at, role FROM identity_users WHERE user_id = ?"
        private const val UPDATE_ROLE_SQL =
            "UPDATE identity_users SET role = ? WHERE user_id = ?"
```

(Leave `UPDATE_LAST_SEEN_SQL`, `UPDATE_DISPLAY_NAME_SQL`, `DELETE_SQL` unchanged.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :identity:infrastructure:test --tests "*PostgresUserRepositoryTest" --tests "*InMemoryUserRepositoryTest"`
Expected: PASS (skips gracefully if Docker is unavailable via `assumeTrue`).

- [ ] **Step 5: Commit**

```bash
git add identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepository.kt \
        identity/infrastructure/src/test/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepositoryTest.kt
git commit -s -m "feat(identity-infrastructure): persist and read identity_users.role"
```

---

## Task 6: `UserRoleChangedBroadcaster` port

**Files:**
- Create: `identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRoleChangedBroadcaster.kt`

No standalone test — it is a single-method interface exercised by Task 7's use-case tests (no logic to test in isolation; mirrors `UserRenamedBroadcaster`).

- [ ] **Step 1: Create the port**

```kotlin
package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Fire-and-forget notification of a role change (ADR-0049). */
fun interface UserRoleChangedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        role: Role,
        changedAt: Instant,
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRoleChangedBroadcaster.kt
git commit -s -m "feat(identity-application): add UserRoleChangedBroadcaster port"
```

> **End of phase A2.** Open the `feat/identity-role-persistence` PR (Tasks 1–6). Run `./gradlew :identity:domain:test :identity:application:test :identity:infrastructure:test --parallel` and `./gradlew spotlessCheck` before pushing.

---

## Task 7: `SetUserRoleUseCase`

**Files:**
- Create: `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCase.kt`
- Create: `identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/FakeUserRepository.kt`
- Create: `identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/RecordingUserRoleChangedBroadcaster.kt`
- Create: `identity/application/src/test/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCaseTest.kt`

The use case returns an **outcome** rather than throwing, because the bootstrap loop (Task 9) processes a batch of ids and a not-found id must not abort the rest.

- [ ] **Step 1: Write the test doubles**

`FakeUserRepository.kt`:

```kotlin
package com.bliss.identity.application.testdoubles

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Minimal in-memory UserRepository for application-layer tests (no infrastructure dependency). */
class FakeUserRepository : UserRepository {
    private val byId = LinkedHashMap<UserId, User>()

    override suspend fun create(user: User) {
        byId.putIfAbsent(user.id, user)
    }

    override suspend fun findById(id: UserId): User? = byId[id]

    override suspend fun updateLastSeenAt(id: UserId, at: Instant) {
        byId[id]?.let { byId[id] = it.copy(lastSeenAt = at) }
    }

    override suspend fun updateDisplayName(id: UserId, name: DisplayName) {
        byId[id]?.let { byId[id] = it.copy(displayName = name) }
    }

    override suspend fun updateRole(id: UserId, role: Role) {
        byId[id]?.let { byId[id] = it.copy(role = role) }
    }

    override suspend fun delete(id: UserId) {
        byId.remove(id)
    }
}
```

`RecordingUserRoleChangedBroadcaster.kt`:

```kotlin
package com.bliss.identity.application.testdoubles

import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import java.time.Instant

class RecordingUserRoleChangedBroadcaster : UserRoleChangedBroadcaster {
    data class Event(val userId: UserId, val role: Role, val changedAt: Instant)

    val events = mutableListOf<Event>()

    override suspend fun broadcast(userId: UserId, role: Role, changedAt: Instant) {
        events.add(Event(userId, role, changedAt))
    }
}
```

- [ ] **Step 2: Write the failing use-case test**

`SetUserRoleUseCaseTest.kt`:

```kotlin
package com.bliss.identity.application.usecases

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.testdoubles.FakeUserRepository
import com.bliss.identity.application.testdoubles.FixedClock
import com.bliss.identity.application.testdoubles.RecordingUserRoleChangedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SetUserRoleUseCaseTest {
    private val now = Instant.parse("2026-05-30T12:00:00Z")
    private val clock = FixedClock(now)

    private fun fixture(role: Role = Role.PLAYER): Pair<FakeUserRepository, UserId> {
        val users = FakeUserRepository()
        val id = UserId(UUID.randomUUID())
        kotlinx.coroutines.runBlocking {
            users.create(User(id, DisplayName.of("Alice"), now, now, role))
        }
        return users to id
    }

    @Test
    fun `promotes a player and broadcasts once`() = runTest {
        val (users, id) = fixture()
        val bc = RecordingUserRoleChangedBroadcaster()
        val outcome = SetUserRoleUseCase(users, bc, clock).execute(id, Role.MAINTAINER)

        assertThat(outcome).isInstanceOf(SetUserRoleOutcome.Changed::class)
        assertThat(users.findById(id)?.role).isEqualTo(Role.MAINTAINER)
        assertThat(bc.events).hasSize(1)
        assertThat(bc.events.first().role).isEqualTo(Role.MAINTAINER)
        assertThat(bc.events.first().changedAt).isEqualTo(now)
    }

    @Test
    fun `unchanged role is a no-op and emits no event`() = runTest {
        val (users, id) = fixture(role = Role.MAINTAINER)
        val bc = RecordingUserRoleChangedBroadcaster()
        val outcome = SetUserRoleUseCase(users, bc, clock).execute(id, Role.MAINTAINER)

        assertThat(outcome).isInstanceOf(SetUserRoleOutcome.Unchanged::class)
        assertThat(bc.events).isEmpty()
    }

    @Test
    fun `unknown user does not broadcast`() = runTest {
        val users = FakeUserRepository()
        val bc = RecordingUserRoleChangedBroadcaster()
        val outcome = SetUserRoleUseCase(users, bc, clock).execute(UserId(UUID.randomUUID()), Role.MAINTAINER)

        assertThat(outcome).isInstanceOf(SetUserRoleOutcome.UserNotFound::class)
        assertThat(bc.events).isEmpty()
    }
}
```

> Note: confirm `FixedClock`'s constructor — the existing `identity/application/src/test/.../testdoubles/FixedClock.kt` takes the fixed `Instant`. If its API differs, adapt the `clock` line only.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :identity:application:test --tests "*SetUserRoleUseCaseTest"`
Expected: FAIL — `SetUserRoleUseCase` / `SetUserRoleOutcome` unresolved.

- [ ] **Step 4: Write the use case**

```kotlin
package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId

sealed interface SetUserRoleOutcome {
    data class Changed(val userId: UserId, val role: Role) : SetUserRoleOutcome
    data class Unchanged(val userId: UserId, val role: Role) : SetUserRoleOutcome
    data class UserNotFound(val userId: UserId) : SetUserRoleOutcome
}

class SetUserRoleUseCase(
    private val users: UserRepository,
    private val broadcaster: UserRoleChangedBroadcaster,
    private val clock: Clock,
) {
    suspend fun execute(
        userId: UserId,
        role: Role,
    ): SetUserRoleOutcome {
        val current = users.findById(userId) ?: return SetUserRoleOutcome.UserNotFound(userId)
        if (current.role == role) return SetUserRoleOutcome.Unchanged(userId, role)
        users.updateRole(userId, role)
        broadcaster.broadcast(userId, role, clock.now())
        return SetUserRoleOutcome.Changed(userId, role)
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :identity:application:test --tests "*SetUserRoleUseCaseTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add identity/application/src/main/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCase.kt \
        identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/FakeUserRepository.kt \
        identity/application/src/test/kotlin/com/bliss/identity/application/testdoubles/RecordingUserRoleChangedBroadcaster.kt \
        identity/application/src/test/kotlin/com/bliss/identity/application/usecases/SetUserRoleUseCaseTest.kt
git commit -s -m "feat(identity-application): SetUserRoleUseCase emits event only on change"
```

---

## Task 8: `NatsUserRoleChangedBroadcaster`

**Files:**
- Create: `identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/NatsUserRoleChangedBroadcaster.kt`

Mirrors `NatsUserRenamedBroadcaster` exactly (fire-and-forget, swallow transport errors). No unit test — matches the existing rename/delete broadcasters, which have none (transport is an external boundary; the payload shape is covered by the AsyncAPI fragment in Task 11).

- [ ] **Step 1: Create the adapter**

```kotlin
package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

@Serializable
private data class UserRoleChangedPayload(
    val userId: String,
    val role: String,
    val changedAt: String,
)

/** Publishes user.role-changed fire-and-forget; transport errors logged and swallowed (ADR-0049). */
class NatsUserRoleChangedBroadcaster(
    private val jetStream: JetStream,
    private val json: Json = Json,
) : UserRoleChangedBroadcaster {
    override suspend fun broadcast(
        userId: UserId,
        role: Role,
        changedAt: Instant,
    ) {
        val payload =
            json.encodeToString(
                UserRoleChangedPayload.serializer(),
                UserRoleChangedPayload(
                    userId = userId.value.toString(),
                    role = role.wire,
                    changedAt = changedAt.toString(),
                ),
            )
        try {
            jetStream.publishAsync(SUBJECT, payload.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            log.warn("user.role-changed publish failed for {}", userId.value, e)
        }
    }

    companion object {
        const val SUBJECT: String = "wordsparrow.user.role-changed"
        private val log = LoggerFactory.getLogger(NatsUserRoleChangedBroadcaster::class.java)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :identity:infrastructure:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/NatsUserRoleChangedBroadcaster.kt
git commit -s -m "feat(identity-infrastructure): NATS UserRoleChanged broadcaster"
```

---

## Task 9: `main()` arg dispatch — `--set-maintainer-roles`

**Files:**
- Modify: `identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt`
- Test: `identity/api/src/test/kotlin/com/bliss/identity/api/MaintainerRoleBootstrapTest.kt` (create)

The id-parsing + per-id orchestration is extracted into an `internal suspend fun setMaintainerRoles(...)` so it is testable without a real DB/NATS. `runSetMaintainerRoles()` wires the real adapters and is the only untested I/O glue.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.identity.api

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.identity.application.testdoubles.FakeUserRepository
import com.bliss.identity.application.testdoubles.FixedClock
import com.bliss.identity.application.testdoubles.RecordingUserRoleChangedBroadcaster
import com.bliss.identity.application.usecases.SetUserRoleUseCase
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MaintainerRoleBootstrapTest {
    private val now = Instant.parse("2026-05-30T12:00:00Z")

    @Test
    fun `promotes each configured id and skips blanks`() = runTest {
        val users = FakeUserRepository()
        val id1 = UserId(UUID.randomUUID())
        val id2 = UserId(UUID.randomUUID())
        runBlocking {
            users.create(User(id1, DisplayName.of("A"), now, now))
            users.create(User(id2, DisplayName.of("B"), now, now))
        }
        val bc = RecordingUserRoleChangedBroadcaster()
        val useCase = SetUserRoleUseCase(users, bc, FixedClock(now))

        val outcomes = setMaintainerRoles(" ${id1.value} , , ${id2.value} ", useCase)

        assertThat(outcomes).containsExactly(
            com.bliss.identity.application.usecases.SetUserRoleOutcome.Changed(id1, Role.MAINTAINER),
            com.bliss.identity.application.usecases.SetUserRoleOutcome.Changed(id2, Role.MAINTAINER),
        )
        assertThat(bc.events.map { it.userId }).containsExactly(id1, id2)
    }

    @Test
    fun `null or blank config yields no work`() = runTest {
        val useCase = SetUserRoleUseCase(FakeUserRepository(), RecordingUserRoleChangedBroadcaster(), FixedClock(now))
        assertThat(setMaintainerRoles(null, useCase)).isEmpty()
        assertThat(setMaintainerRoles("   ", useCase)).isEmpty()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :identity:api:test --tests "*MaintainerRoleBootstrapTest"`
Expected: FAIL — `setMaintainerRoles` unresolved.

- [ ] **Step 3: Modify `Main.kt`**

Add imports and the dispatch. The existing `fun main()` becomes argument-aware; the server path is unchanged when no recognised flag is present.

```kotlin
package com.bliss.identity.api

import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.application.usecases.SetUserRoleOutcome
import com.bliss.identity.application.usecases.SetUserRoleUseCase
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.events.NatsConnectionFactory
import com.bliss.identity.infrastructure.events.NatsUserRoleChangedBroadcaster
import com.bliss.identity.infrastructure.persistence.IdentityDatabase
import com.bliss.identity.infrastructure.persistence.PostgresUserRepository
import com.bliss.identity.infrastructure.time.SystemClock
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("identity-api")

// Entrypoint. With `--set-maintainer-roles`, runs the configure-in-cluster role
// bootstrap (ADR-0060) and exits; otherwise starts the HTTP server.
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--set-maintainer-roles") {
        exitProcess(runSetMaintainerRoles())
    }

    val config = IdentityApiConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: config.port
    val db =
        IdentityDatabase(
            poolName = "identity-api",
            maxPoolSize = 10,
            requireUrl = true,
        ).apply { start() }
    val dataSource = db.dataSource() ?: error("IdentityDatabase did not produce a DataSource.")
    val natsUrl = System.getenv("NATS_URL") ?: error("NATS_URL env var is required")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(config, dataSource, CIO.create(), natsUrl)
    }.start(wait = true)
}

// Pure orchestration: parse the comma-separated id list and promote each to MAINTAINER.
internal suspend fun setMaintainerRoles(
    rawIds: String?,
    useCase: SetUserRoleUseCase,
): List<SetUserRoleOutcome> =
    (rawIds ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { useCase.execute(UserId.parse(it), Role.MAINTAINER) }

// I/O glue: wire real Postgres + NATS adapters, run the bootstrap, log outcomes. Returns the process exit code.
private fun runSetMaintainerRoles(): Int {
    val db =
        IdentityDatabase(poolName = "identity-role-bootstrap", maxPoolSize = 2, requireUrl = true)
            .apply { start() }
    val dataSource = db.dataSource() ?: error("IdentityDatabase did not produce a DataSource.")
    val natsUrl = System.getenv("NATS_URL") ?: error("NATS_URL env var is required")
    val (connection, jetStream) = NatsConnectionFactory(natsUrl).connect()
    return try {
        val useCase =
            SetUserRoleUseCase(
                users = PostgresUserRepository(dataSource),
                broadcaster = NatsUserRoleChangedBroadcaster(jetStream),
                clock = SystemClock,
            )
        val outcomes = runBlocking { setMaintainerRoles(System.getenv("MAINTAINER_USER_IDS"), useCase) }
        outcomes.forEach { outcome ->
            when (outcome) {
                is SetUserRoleOutcome.Changed -> log.info("event=role_set user={} role={}", outcome.userId, outcome.role.wire)
                is SetUserRoleOutcome.Unchanged -> log.info("event=role_unchanged user={} role={}", outcome.userId, outcome.role.wire)
                is SetUserRoleOutcome.UserNotFound -> log.warn("event=role_user_not_found user={}", outcome.userId)
            }
        }
        0
    } catch (e: Throwable) {
        log.error("event=role_bootstrap_failed reason={}", e.message, e)
        1
    } finally {
        connection.close()
    }
}
```

> Note: confirm `SystemClock` is an `object` implementing `Clock` (it is referenced this way in `Wiring.forProduction`). If `IdentityDatabase`'s constructor signature differs from what `Main.kt` already uses, copy it verbatim from the existing server path above (it is unchanged).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :identity:api:test --tests "*MaintainerRoleBootstrapTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/MaintainerRoleBootstrapTest.kt
git commit -s -m "feat(identity-api): --set-maintainer-roles bootstrap entrypoint"
```

---

## Task 10: Helm bootstrap Job + values

**Files:**
- Create: `identity/api/deploy/chart/templates/job-maintainer-roles.yaml`
- Modify: `identity/api/deploy/chart/values.yaml`
- Modify: `identity/api/deploy/chart/values-prod.yaml`

- [ ] **Step 1: Add the values block** to `values.yaml` (after `envFromSecret`):

```yaml
# Configure-in-cluster maintainer role assignment (ADR-0060). The post-upgrade
# Job promotes each listed identity user_id to role=maintainer and emits
# UserRoleChanged. Off by default; prod enables it. Requires database.enabled.
maintainerRoleBootstrap:
  enabled: false
  # Comma-separated identity user UUIDs. Empty -> Job runs and no-ops.
  userIds: ""
```

- [ ] **Step 2: Enable it in `values-prod.yaml`** (add the block; fill the real UUID at deploy time — it is obtainable from the survey `proposed_by` rows):

```yaml
maintainerRoleBootstrap:
  enabled: true
  userIds: "REPLACE_WITH_MAINTAINER_UUID"
```

> The literal `REPLACE_WITH_MAINTAINER_UUID` is intentional: the value is an operational secret-ish input filled by the operator at deploy time, not a code constant. The Job no-ops safely if left unset.

- [ ] **Step 3: Create the Job template** `job-maintainer-roles.yaml`:

```yaml
{{- if .Values.maintainerRoleBootstrap.enabled }}
{{/* Post-upgrade: promotes configured user_ids to maintainer and emits UserRoleChanged (ADR-0060). Idempotent. */}}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "bliss-identity-api.fullname" . }}-maintainer-roles
  annotations:
    "helm.sh/hook": "post-install,post-upgrade"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
    "helm.sh/hook-weight": "5"
  labels: {{- include "bliss-identity-api.labels" . | nindent 4 }}
spec:
  ttlSecondsAfterFinished: 600
  backoffLimit: 3
  template:
    metadata:
      # selectorLabels so the bliss-nats NetworkPolicy admits this Job on 4222.
      labels: {{- include "bliss-identity-api.selectorLabels" . | nindent 8 }}
    spec:
      restartPolicy: OnFailure
      serviceAccountName: {{ include "bliss-identity-api.serviceAccountName" . }}
      securityContext: {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: set-maintainer-roles
          image: {{ include "bliss-identity-api.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext: {{- toYaml .Values.containerSecurityContext | nindent 12 }}
          command: ["java", "-jar", "/app/identity-api.jar", "--set-maintainer-roles"]
          env:
            {{- if .Values.database.enabled }}
            - name: IDENTITY_DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.database.clusterName }}-app
                  key: uri
            {{- end }}
            - name: MAINTAINER_USER_IDS
              value: {{ .Values.maintainerRoleBootstrap.userIds | quote }}
            {{- with .Values.env }}
            {{- toYaml . | nindent 12 }}
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

> Verify the helper names `bliss-identity-api.fullname`, `.labels`, `.selectorLabels`, `.serviceAccountName`, `.image` exist in `identity/api/deploy/chart/templates/_helpers.tpl` (the deployment uses `bliss-identity-api.image`; the survey chart uses the analogous `bliss-survey-api.*` set). If a helper name differs, match the deployment template's spelling.

- [ ] **Step 4: Lint the chart**

Run: `helm lint identity/api/deploy/chart --values identity/api/deploy/chart/values-prod.yaml`
Expected: `1 chart(s) linted, 0 chart(s) failed`.

Also render to eyeball the Job:

Run: `helm template identity/api/deploy/chart --values identity/api/deploy/chart/values-prod.yaml --show-only templates/job-maintainer-roles.yaml`
Expected: a Job manifest with `command: ["java","-jar","/app/identity-api.jar","--set-maintainer-roles"]` and `MAINTAINER_USER_IDS` set.

- [ ] **Step 5: Commit**

```bash
git add identity/api/deploy/chart/templates/job-maintainer-roles.yaml \
        identity/api/deploy/chart/values.yaml \
        identity/api/deploy/chart/values-prod.yaml
git commit -s -m "feat(identity-api): post-upgrade Job assigns maintainer roles"
```

> **End of phase A3.** Open the `feat/identity-role-bootstrap` PR (Tasks 7–10), stacked on A2. Include the threat model (from the spec / ADR-0060) in the PR body.

---

## Task 11: AsyncAPI event fragment (phase A1, lands first)

**Files:**
- Create: `identity/api/events/UserRoleChanged.yaml`

- [ ] **Step 1: Create the fragment** (mirrors `identity/api/events/UserDeleted.yaml`):

```yaml
summary: A user's role changed; downstream consumers update their cached role state.
payload:
  type: object
  required: [userId, role, changedAt]
  properties:
    userId:    { type: string, format: uuid }
    role:
      type: string
      enum: [player, maintainer]
      x-enum-varnames: [PLAYER, MAINTAINER]
    changedAt: { type: string, format: date-time }
```

- [ ] **Step 2: Commit**

```bash
git add identity/api/events/UserRoleChanged.yaml
git commit -s -m "docs(identity): UserRoleChanged event contract"
```

---

## Task 12: ADR-0060 + INDEX.md (phase A1)

**Files:**
- Create: `docs/adr/0060-identity-user-roles.md`
- Modify: `docs/adr/INDEX.md`

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0060: Identity user roles + `UserRoleChanged` event

## Status
Accepted

## Context
The survey context needs to give *maintainer-authored* correctifs gold training
weight (Specs B–D of the 2026-05-30 clue-gen gold-weighting rollout). That
requires distinguishing a maintainer from any other authenticated rater. Users
live in the identity bounded context; survey cannot import identity and learns
about users only through NATS events. We also anticipate other role-gated
features (admin/moderation/campaign control), so the primitive should be
reusable rather than single-use.

## Decision
Add a `role` column to `identity_users` (`player` default, `maintainer`) and a
`Role` domain type. Role changes publish a fire-and-forget `UserRoleChanged`
event on `wordsparrow.user.role-changed` (ADR-0049 posture), which survey (and
future consumers) cache.

Roles are assigned only by a configure-in-cluster Helm `post-install,post-upgrade`
bootstrap Job that runs the identity image with `--set-maintainer-roles` and a
configured `MAINTAINER_USER_IDS` list. There is deliberately **no HTTP
role-mutation endpoint** in this ADR.

### Threat model
- **Asset:** the `maintainer` role (confers gold training weight today; more
  later).
- **Mutation surface:** only the bootstrap Job. The id list is a chart value /
  k8s Secret, never code; a DB write needs cluster access. No runtime
  privilege-escalation path, no IDOR on a role route.
- **Event exposure:** internal NATS subject, NetworkPolicy-guarded; payload
  carries `userId`, `role`, `changedAt` — no secrets.
- **Event-loss failure mode:** tolerable. The Job re-runs on every
  `helm upgrade` (re-emitting only on actual change), and consumers must be
  idempotent with their own reconciliation. Delivery is not guaranteed.
- **Spoofing:** only identity publishes to the subject; consumers trust the
  in-cluster broker, as with `user.deleted`.

## Consequences
- Easier: a single reusable authz primitive for current and future role gates;
  survey can gate `training_weight` on a cached maintainer role.
- Harder: cross-context role propagation now has a contract to maintain
  (`UserRoleChanged`); consumers must handle best-effort delivery.
- Deferred: a runtime role-management API (YAGNI until a second assignment need).
```

- [ ] **Step 2: Register in `INDEX.md`** — add a row to the table (keep alignment with neighbouring rows):

```
ADR-0060  identity/**                              Identity user roles + UserRoleChanged event (gold-weighting Spec A)
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0060-identity-user-roles.md docs/adr/INDEX.md
git commit -s -m "docs(identity): ADR-0060 user roles + UserRoleChanged event"
```

> **Phase A1** = Tasks 11–12 on `docs/identity-roles-adr-event` off `main`; lands first (schema-first, ADR before code per ADR-0001 §7). The `registry-coherence` CI gate is satisfied because the ADR and INDEX.md change together.

---

## Final verification (before each PR)

- [ ] `./gradlew :identity:domain:test :identity:application:test :identity:infrastructure:test :identity:api:test --parallel` — all green.
- [ ] `./gradlew spotlessCheck` — clean (run `spotlessApply` to fix).
- [ ] Konsist arch tests pass (run as part of the module test tasks): no `domain`/`application` → vendor-SDK imports; the NATS adapter stays in `infrastructure`.
- [ ] `helm lint` + `helm template` for the Job (Task 10 Step 4).
- [ ] Each PR body ≤ 400 net diff lines, or invokes the cap-override with justification.

## Out of scope (Specs B–D)

Survey consumer for `UserRoleChanged`, the survey-side role cache, the
`training_weight` column + correctif gating (role==maintainer ∧
created_at ≥ 2026-05-30), `ExportDatasetUseCase` changes, and wiring the survey
export into `build_modal_corpus.py`. Each is its own spec → plan.
