---
name: jvm-backend
description: Implement, test, or fix Kotlin code under grid/ or game/ bounded contexts. Stack is Ktor 3.x + Kotlin + Postgres (CNPG) + Flyway + kotlinx-coroutines + Konsist + JUnit 5 + AssertJ + Kotest + Testcontainers. Use when adding a domain type / use case / Ktor route / Flyway migration, when Gradle compile or Spotless fails, when Konsist arch tests fail, when a Dockerfile breaks because settings.gradle.kts added a module, or when CI's gradle-build job is red. Encodes the bounded-context layout, the Konsist-enforced layer rules, and every Kotlin/Gradle gotcha that has actually bitten a real PR in this repo.
paths: ["grid/**", "game/**", "*.gradle.kts", "settings.gradle.kts"]
---

# JVM backend playbook

For Kotlin work under any bounded context. Two contexts today: `grid/` (stateless puzzle generation) and `game/` (lobbies + multiplayer game state, in flight). Same conventions, same stack.

## Anchor documents

- `docs/adr/0001-parallel-agent-development-workflow.md` §1 — **no cross-context imports**. `game:domain` does not import `com.bliss.grid.*`. Communication is via API (HTTP) only.
- `docs/adr/0006-jvm-http-framework.md` — Ktor 3.x is the binding choice. SSE for v1, WebSocket added with the multiplayer rollout.
- `docs/adr/0009-self-managed-k8s-deployment.md` — k3s + Helm + CNPG (CloudNativePG) for Postgres.
- `docs/adr/0013-words-clues-worker.md` — the worker pattern (`grid:worker` is local-dev only).
- Per-context ADRs: `0018-game-bounded-context-and-realtime.md` for the game/ context, `0015-skeleton-based-grid-generator.md` etc. for grid.

## Bounded-context layout

```
<context>/
├── domain/             # pure Kotlin types + invariants. ZERO framework deps.
├── application/        # use cases + ports (interfaces). kotlinx-coroutines + slf4j only.
├── infrastructure/     # adapters: Postgres (HikariCP + jdbc), HTTP clients, etc.
├── api/                # Ktor module. Only this layer imports Ktor.
└── worker/             # OPTIONAL local-dev CLI (grid:worker only). Clikt for argv.
```

Each layer is its own Gradle module. Module names: `:grid:domain`, `:grid:application`, `:grid:infrastructure`, `:grid:api`, `:grid:worker`, and the `:game:*` mirror.

## Layer dependency direction (Konsist-enforced)

```
api → infrastructure → application → domain
```

Strict one-way:
- `domain` imports nothing from this repo.
- `application` imports `domain` only.
- `infrastructure` imports `application` + `domain`.
- `api` imports all three.
- **No** cross-context import (e.g. `com.bliss.game.*` from `grid/`).

Each module ships its own `architecture/` Konsist test mirroring `:grid:domain`'s `DomainArchitectureTest`. When you add a module, copy that test file and adapt the package check.

## Domain layer

- `data class` for state, `sealed interface` / `sealed class` for discriminated unions, `enum class` for closed sets, `value class` (typically wrapping `String` or `Long`) for IDs.
- Validation in init blocks: `require(width in 5..15) { "width out of range: $width" }`.
- Domain factories like `LobbyId.generate()` use `java.security.SecureRandom` — no new Gradle deps for randomness.
- No coroutines, no `@Serializable`, no `Logger`, no `Instant.now()` (inject a `Clock` port from `application`).
- Tests: ~100 % mutation coverage on the lifecycle logic. Use Kotest property-based tests for serialization-style invariants.

## Application layer

- Ports are Kotlin `interface`s. Suffixes: `Repository` (state I/O), `Provider` (pulls), `Broadcaster` (pushes), `Clock` (time).
- Use cases are classes with a `suspend operator fun invoke(...)`.
- Return shape: pick one for the module and stick with it. `:game:application` uses `UseCaseOutcome<T>` (sealed `Success(value, events) | Failure(UseCaseError)`).
- Atomicity: when a port exposes `mutate(id, lambda)` (per-key lock), put **all** state guards inside the lambda. Reading state outside the lock and validating it there is a TOCTOU bug — see PR #127's review for the canonical example.
- IO outside the lock when possible: e.g. fetch a remote resource before calling `mutate`, then pass the result into the lambda. Holding a per-lobby lock while making an HTTP call stalls other lobbies.

## Infrastructure layer

- Postgres via HikariCP + raw JDBC. No JPA, no Exposed (per current grid/ precedent — keep it consistent).
- Flyway migrations under `<context>/api/src/main/resources/db/migration/` (`V<n>__<description>.sql`). Forward-only, idempotent.
- Repository contract tests use Testcontainers' `PostgreSQLContainer` against the real Flyway-applied schema.
- Configuration via env: `DATABASE_URL` (CNPG style: `postgres://user:pw@host/db`), service-specific overrides.
- HTTP client: Ktor client (`io.ktor:ktor-client-core` + a CIO or OkHttp engine). Don't add Retrofit, don't add Apache HTTP — Ktor only.
- Serialization: `kotlinx-serialization-json` (already in scope via Ktor `ContentNegotiation`).

## API layer (Ktor)

- Single `Application.module()` entrypoint per context, mirroring `grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt`.
- Plugins to install: `ContentNegotiation` (kotlinx JSON), `CallLogging`, `CORS` (only the production hosts + localhost dev), `StatusPages` (RFC 7807 catch-all), `WebSockets` (game/ only).
- Routes are top-level `Route.<contextRouteName>(...)` extension functions. Wire them up in `Module.kt`'s `routing { }`.
- Health endpoint at `/v1/health`. Probes (Kubernetes liveness / readiness / startup) are configured in the Helm chart.
- Error handling: throw a domain exception → `StatusPages` maps to RFC 7807 problem-detail JSON with the right `type`, `status`, `detail`.

## Test stack

- **JUnit 5 platform** — the launcher.
- **AssertJ** — for fluent assertions on Kotlin objects (`assertThat(...)`).
- **Kotest** — property-based tests (`Arb.int(...)`, `forAll(...)`).
- **Testcontainers** — real Postgres for repository contract tests + integration tests.
- **Konsist** — architecture tests (per-module).
- **No Mockito of own classes.** Per CLAUDE.md: mock external boundaries only (Ktor `HttpClient`, Postgres if you must — but Testcontainers is preferred). For your own code, use real instances or in-memory implementations (`InMemoryLobbyRepository` for game-application tests).

## Test naming — ASCII only

**This rule has cost a CI day.** Em-dashes (`—`) and other non-ASCII characters in `@Test fun \`...\`` names crash `compileTestKotlin` under POSIX-locale CI runners:

```
java.nio.file.InvalidPathException: Malformed input or input contains
unmappable characters: …/PuzzleRouteTest$response body deserializes
as PuzzleResponse — schema drift guard$1$1.class
```

JVM-arg workarounds (`-Dfile.encoding=UTF-8`, `-Dsun.jnu.encoding=UTF-8`) **do not reliably help** because `sun.jnu.encoding` is read at JVM startup before `-D` flags apply. Just use ASCII hyphens. Em-dashes in COMMENTS are fine — they don't become file path components.

## Spotless ktlint

Spotless (ktlint 1.5.0, applied to every subproject in the root `build.gradle.kts`) runs on `src/**/*.kt` and `*.gradle.kts`. It's the single most frequent CI failure on Kotlin PRs because the muscle-memory is "compile and push" — but `compileKotlin` doesn't run Spotless, so the violation only surfaces in CI.

**Always `spotlessApply` before pushing, not just `spotlessCheck`.**

```
./gradlew spotlessApply        # auto-fix
./gradlew spotlessCheck        # verify (this is what CI runs)
```

Two important properties:

- **CI runs `spotlessCheck` on every module**, not just the modules you touched. So a stale violation introduced by a prior PR (rare, but happens after a ktlint version bump) will fail your build too. If `spotlessCheck` fails on a module your PR didn't touch, run `spotlessApply` repo-wide and commit the result as a separate `chore(spotless): re-format after ktlint bump` commit — don't mix it into the feature PR's diff.
- **Spotless target** = `src/**/*.kt` (test + main) + `*.gradle.kts`. Test files count. New `*.gradle.kts` at a new module's root counts too.

**Pre-push check pattern (recommended):**

```
./gradlew :<scope>:check :<scope>:spotlessCheck
```

Or repo-wide:

```
./gradlew check    # runs spotlessCheck on every module as part of check
```

If you're inside the §6a fixer loop and Spotless is the only CI failure, the fix is usually a one-line commit: `./gradlew spotlessApply && git add -u && git commit -s -m "chore(<scope>): apply spotless"`. Don't suppress ktlint findings; the formatter is the authority per CLAUDE.md.

**Common triggers** (in order of frequency):
- Import sort / unused-import removal after adding-then-removing a class.
- Trailing comma missing in multi-line argument lists (ktlint 1.5 prefers them).
- Blank-line conventions around top-level / nested function declarations.
- `*.gradle.kts` files (often the new module's build script is committed unformatted).

## Build commands

Fast iteration on a single module:

```
./gradlew :game:application:check :game:application:spotlessCheck
```

Full repo (matches CI):

```
./gradlew check
```

`check` runs ALL modules' tests + Spotless + Konsist arch tests. The first run is slow because of the Kotlin daemon spin-up; subsequent runs benefit from configuration cache (`org.gradle.configuration-cache=true` is on).

## The `settings.gradle.kts` ↔ Dockerfile gotcha

This pattern has bitten **three** PRs in the multiplayer rollout (#126, #127, and Wave D will hit it again):

When `settings.gradle.kts` adds a module via `include(":game:foo")`, Gradle's configuration phase requires `/workspace/game/foo/` to **exist as a directory** before any task can run — even tasks that don't depend on that module. So when `grid/api/Dockerfile` runs `./gradlew :grid:api:shadowJar`, Gradle blows up with:

```
Configuring project ':game:foo' without an existing directory is not allowed.
```

**Fix**: every Dockerfile that runs `./gradlew :…:shadowJar` must `COPY <new-module>/build.gradle.kts <new-module>/` for the new module. Source tree is **not** needed — just the build script — because the requested task doesn't depend on it.

Mirror the existing block in `grid/api/Dockerfile`:

```dockerfile
# settings.gradle.kts include(":game:domain") forces gradle to evaluate
# the project at configuration time, even though :grid:api:shadowJar does
# not depend on it. Copying the build script is enough to satisfy the
# project-directory check; src/ is intentionally not copied.
COPY game/domain/build.gradle.kts game/domain/
COPY game/application/build.gradle.kts game/application/
# ... and so on for each new :game:* module.
```

If the recurrence becomes annoying, the long-term fix is either (a) glob the COPY (`COPY game/*/build.gradle.kts game/`) — but globs don't preserve directory structure cleanly with `COPY` — or (b) restructure to a single `COPY . .` with an aggressive `.dockerignore`. Both are out of scope for any single feature PR; the recurring two-line fix is acceptable for now.

## Coroutines

- Use `suspend` everywhere in application use cases. The HTTP client is async; the WS handlers are async.
- Don't use `runBlocking` outside `main` and tests.
- Structured concurrency: prefer `coroutineScope { }` to keep cancellation propagation correct.
- Tests: `runTest { }` from `kotlinx-coroutines-test`. The `:game:application` `Fakes.kt` shows the pattern.

## Configuration cache

`org.gradle.configuration-cache=true` is on. This means:
- Build configuration runs once and caches.
- Task graph is reused across invocations.
- Some tasks are configuration-cache-incompatible — they show `> Task :foo:bar` followed by an exception about "Configuring … without an existing directory" or similar. The Dockerfile gotcha above is one symptom.

## kotlinx-serialization: `encodeDefaults = true` on every wire-DTO `Json` builder

ADR-0003 §6 requires every field in an OpenAPI/AsyncAPI `required` array to appear on the wire. kotlinx-serialization's default is to **omit** properties whose runtime value equals the declared default — including defaulted collections (`emptyList()`, `emptyMap()`, `emptySet()`). A `data class GameSession(val players: List<Player> = emptyList(), …)` will serialize to `{...}` with no `players` key when the list is empty, breaking the wire contract.

The fix is one line on every `Json { … }` builder that serializes a DTO:

```kotlin
val wireJson = Json {
    encodeDefaults = true    // ADR-0003 §6 — required defaulted fields MUST be on the wire
    ignoreUnknownKeys = true
    explicitNulls = true     // nullable: true distinct from absence
}
```

This applies to:
- The Ktor `ContentNegotiation` `json(…)` configuration.
- Any per-route or per-handler `Json` instance.
- The default `Json` used by the `WebSockets` plugin for outgoing frame serialization.
- Any test helper that round-trips through `Json.encodeToString(…)`.

Add a regression test for at least one defaulted-collection field per context, asserting the serialized JSON contains the key with `[]` or `{}` — not absence. PR #401 cycled **four** review cycles on three separate `Json {}` builders missing this flag (REST, route-level, default). The pattern is mechanical and the test is one assertion.

## Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `Configuring project ':game:X' without an existing directory` in Docker build | `settings.gradle.kts` added a module; the Dockerfile didn't copy its `build.gradle.kts` | Add `COPY game/X/build.gradle.kts game/X/` to every relevant Dockerfile. |
| OpenAPI consumer (frontend, integration test) sees a `required` field absent | kotlinx-serialization `Json {}` builder lacks `encodeDefaults = true`; defaulted collection serialized as omitted | Add `encodeDefaults = true` to every wire-facing `Json` builder. PR #401. |
| `InvalidPathException: Malformed input` writing a class file | Em-dash in a `@Test` name | Replace `—` with ASCII `-`. |
| `dco` CI fails | Missing `Signed-off-by:` trailer | `git commit -s --amend --no-edit && git push --force-with-lease`. Multi-commit branches: `git rebase --signoff origin/main`. |
| `commitlint` rejects multi-scope | `fix(grid-api,grid-worker):` (commas not allowed) | Single hyphenated scope: `fix(grid):`. |
| Konsist arch test fails: "boundary violation" | Imported across layers or contexts | Add the missing port in `application`; have `infrastructure` implement; consume from `api`. NEVER suppress. |
| Spotless fails on imports | New imports added; ktlint hasn't sorted them | `./gradlew spotlessApply`. |
| Test passes locally, fails in CI with race | TOCTOU between a snapshot read and a `mutate` call | Move the guard inside the `mutate` lambda. PR #127's review documents the pattern. |
| Flyway migration fails in CI | Wrong version number / non-idempotent SQL | Migrations are forward-only and immutable once shipped. New migration = next `V<n>` number. |

## Don'ts

- **Don't** import across bounded contexts. Use HTTP at the API boundary.
- **Don't** add a framework dep to `domain`. Pure Kotlin only.
- **Don't** use `Mockito` to fake your own classes. In-memory implementations.
- **Don't** put non-ASCII characters in `@Test fun \`...\`` names.
- **Don't** validate state outside `repo.mutate(...)`'s lambda when you intend to mutate based on it. TOCTOU bug.
- **Don't** hold a per-lobby lock across HTTP calls. Fetch first, then pass the result into the lambda.
- **Don't** introduce new logging frameworks. slf4j-api in application + log4j2/logback in api only.
- **Don't** add a Spring Boot dep "for convenience". Ktor is the binding choice.
- **Don't** edit generated SQL or Konsist test reports.
- **Don't** suppress Spotless / Konsist with `@Suppress`. Fix the underlying issue.
- **Don't** forget the Dockerfile when adding a `:game:*` module to `settings.gradle.kts`.
- **Don't** instantiate a wire-facing `Json {}` builder without `encodeDefaults = true`. Defaulted required fields (empty collections especially) silently drop from the wire — see the dedicated section above; PR #401.
- **Don't** reference PR / issue numbers or "fixed in #N" in Kotlin source comments. Those rot once the branch merges. Cite an ADR or move the rationale to the PR body. Recurring: PRs #353, #367, #376, #399, #405.
- **Don't** write multi-line / multi-paragraph KDoc or `//` comment blocks. CLAUDE.md: "one short line max" in source; deeper rationale belongs in the PR body or an ADR. Recurring: PRs #364, #389, #401.
