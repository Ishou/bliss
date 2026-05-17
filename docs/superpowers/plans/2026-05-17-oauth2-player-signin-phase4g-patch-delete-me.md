# OAuth2 Player Sign-In — Phase 4g: PATCH + DELETE /v1/users/me

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Add `PATCH /v1/users/me` (update display name) and `DELETE /v1/users/me` (close account). Both auth-gated; both extend the existing `MeRoute.kt` from #496.

**Architecture:**
- Both routes live in the existing `MeRoute.kt`.
- PATCH: parses `{ "displayName": "..." }` body, calls `UpdateMeUseCase`, returns 200 with the updated `MeResponse`. On `UpdateMeError.InvalidDisplayName`, returns 400 + `problem+json`.
- DELETE: calls `DeleteUserUseCase` (which revokes sessions, broadcasts the event, persists the tombstone), clears the `__Host-ws_session` cookie, returns 204. No body.
- `UpdateMeUseCase` and `DeleteUserUseCase` are already wired in `Wiring.forProduction(...)`. Add `updateMeOrNull` and `deleteUserOrNull` peek accessors.
- The PATCH response shape MUST be identical to GET — re-use `MeResponse`.

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-patch-delete-me
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/dto/UpdateMeRequest.kt`
- (Extend) `identity/api/src/test/kotlin/com/bliss/identity/api/routes/MeRouteTest.kt` — add PATCH + DELETE tests, OR create a sibling test file if MeRouteTest is already large.

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — add `updateMeOrNull` + `deleteUserOrNull`.
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/MeRoute.kt` — add `patchMe(...)` + `deleteMe(...)` route extensions.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount both.

---

## Task: PR 4g — PATCH + DELETE /v1/users/me

**Branch:** `feat/oauth2-patch-delete-me`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/.../usecases/UpdateMeUseCase.kt`. Confirm: command shape (`UpdateMeCommand(userId, displayName)`?), result type (does it return the updated user/profile, or just `Unit`?), error subclasses (likely `UpdateMeError.InvalidDisplayName`).

- [ ] **Step 0b:** Read `identity/application/.../usecases/DeleteUserUseCase.kt`. Confirm: command shape (`DeleteUserCommand(userId)`?), result, errors. Confirm the broadcaster + session-revocation side effects happen inside the use case (so the route just calls + clears cookie).

- [ ] **Step 0c:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/routes/MeRoute.kt` (from #496). Note the import set + helper conventions to extend cleanly.

- [ ] **Step 0d:** Read `identity/api/openapi.yaml` for `PATCH /v1/users/me` and `DELETE /v1/users/me`. Confirm:
  - PATCH request body schema (likely `{ displayName: string }`), response (200 with `MeResponse` — same as GET), error shapes (400 invalid_display_name).
  - DELETE — no body, 204 success, auth required.

- [ ] **Step 0e:** Read `identity/domain/.../user/DisplayName.kt` to confirm validation rules (length, allowed chars). The use case's `InvalidDisplayName` error is what the route translates to 400.

- [ ] **Step 0f:** If GET's response is built by calling `GetMeUseCase` after the update, the PATCH route can reuse that pattern (call `UpdateMeUseCase` then `GetMeUseCase`). But if `UpdateMeResult` already carries the full profile, use it directly to avoid a second query. Pick based on what's actually returned.

### 1 — Wiring peek accessors

- [ ] **Step 1:** Add to `Wiring.kt`:

```kotlin
internal val updateMeOrNull: UpdateMeUseCase? get() = _updateMe
internal val deleteUserOrNull: DeleteUserUseCase? get() = _deleteUser
```

- [ ] **Step 2:** Commit:

```bash
./gradlew :identity:api:build --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt
git commit -s -m "feat(identity-api): expose updateMe + deleteUser peek accessors"
```

### 2 — Request DTO

- [ ] **Step 3:** Create `UpdateMeRequest.kt`:

```kotlin
package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateMeRequest(
    val displayName: String,
)
```

If the OpenAPI permits other fields (e.g. partial updates) verify and adjust. If `displayName` is the only field, keep it simple.

### 3 — Routes

- [ ] **Step 4:** Extend `MeRoute.kt`. Add `patchMe(...)` and `deleteMe(...)` route extensions next to the existing `me(...)`. Example shape (verify use case signatures against Step 0a/0b):

```kotlin
fun Route.patchMe(
    updateMe: UpdateMeUseCase,
    getMe: GetMeUseCase,
    whoAmI: WhoAmIUseCase,
    json: Json = Json,
) {
    patch("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@patch
        val request = try {
            call.receive<UpdateMeRequest>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@patch call.problem(
                json, HttpStatusCode.BadRequest, "invalid_body",
                "Request body must be {\"displayName\": string}.",
            )
        }

        try {
            updateMe.execute(UpdateMeCommand(userId = auth.userId, displayName = request.displayName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UpdateMeError.InvalidDisplayName) {
            return@patch call.problem(
                json, HttpStatusCode.BadRequest, "invalid_display_name",
                e.message ?: "Display name is invalid.",
            )
        }

        // Re-read profile so the PATCH response shape matches GET.
        // If UpdateMeUseCase already returns the updated user + linked providers, skip this and
        // build the MeResponse from its result directly.
        val result = getMe.execute(GetMeQuery(userId = auth.userId))
        call.respond(HttpStatusCode.OK, result.toMeResponse())
    }
}

fun Route.deleteMe(
    deleteUser: DeleteUserUseCase,
    whoAmI: WhoAmIUseCase,
) {
    delete("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@delete
        deleteUser.execute(DeleteUserCommand(userId = auth.userId))
        SessionCookies.clear(call)
        call.respond(HttpStatusCode.NoContent)
    }
}
```

Notes:
- `call.problem(...)` lives in `CallbackErrorMapping.kt` (extracted in #494). It's an extension on `RoutingCall` — import + reuse, don't re-declare.
- `MeResponse` + `toMeResponse()` helper: if `MeRoute.kt` already defines a private helper that maps `GetMeResult → MeResponse`, reuse it. If it inlines the mapping, extract a small `private fun GetMeResult.toMeResponse(): MeResponse = ...` and reuse from both GET and PATCH.
- `UpdateMeError` exhaustive `when`: read the sealed class. If there are more subclasses than `InvalidDisplayName`, map exhaustively.
- `DeleteUserError`: if the use case throws errors (e.g. `BroadcastFailed`), they should likely still result in 204 from the client's perspective IF the user is already marked deleted server-side. Otherwise map to 503. Read the use case to decide.
- If `UpdateMeUseCase` returns the full updated profile (Step 0f), drop the extra `getMe.execute(...)` call and the `getMe` parameter.

- [ ] **Step 5:** Update `Module.kt` to mount. Add inside the existing `routing { ... }` block:

```kotlin
val getMe = wiring.getMeOrNull
val whoAmI = wiring.whoAmIOrNull
if (getMe != null && whoAmI != null) {
    me(getMe, whoAmI)
    wiring.updateMeOrNull?.let { updateMe ->
        patchMe(updateMe, getMe, whoAmI)
    }
}
val deleteUser = wiring.deleteUserOrNull
if (deleteUser != null && whoAmI != null) {
    deleteMe(deleteUser, whoAmI)
}
```

(If the existing `me(...)` mount in Module.kt already pattern-matches differently, adapt to it. The goal: PATCH mounts only when both `updateMe` + `getMe` + `whoAmI` are wired; DELETE mounts when `deleteUser` + `whoAmI` are wired.)

### 4 — Tests

- [ ] **Step 6:** Extend `MeRouteTest.kt` (or create a sibling file `PatchDeleteMeRouteTest.kt` if `MeRouteTest.kt` is already long). Add:

```kotlin
@Test
fun `patch with invalid body returns 400`() = testApplication {
    // POST with malformed JSON or missing displayName.
}

@Test
fun `patch with invalid display name returns 400`() = testApplication {
    // displayName = "" or too long. Assert problem+json type contains "invalid_display_name".
}

@Test
fun `patch with valid display name returns 200 with updated profile`() = testApplication {
    // PATCH {"displayName":"NewName"}, assert 200 + body.displayName == "NewName".
}

@Test
fun `patch without cookie returns 401`() = testApplication { /* ... */ }

@Test
fun `delete with valid session returns 204 + clears cookie + persists user as deleted`() = testApplication {
    // After DELETE, assert: 204, Set-Cookie with Max-Age 0, users.findById(...) returns the tombstoned user
    // (or null, depending on the soft/hard delete semantics — verify against DeleteUserUseCase behavior).
    // Also assert all sessions for the user are revoked (sessions.findActive(userId) is empty).
}

@Test
fun `delete without cookie returns 401`() = testApplication { /* ... */ }
```

Helper: extend the existing `newWiring(...)` from `MeRouteTest.kt` to optionally wire `updateMe`/`deleteUser` use cases with the same in-memory adapters. If `MeRouteTest.kt`'s helper is private/closed, copy the pattern into the new test file.

The DELETE side-effect test is load-bearing: assert (1) cookie cleared, (2) user marked deleted server-side, (3) sessions revoked. Read `DeleteUserUseCase`'s implementation to know what state-changes to assert.

- [ ] **Step 7:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/dto/UpdateMeRequest.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/routes/MeRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/MeRouteTest.kt
git commit -s -m "feat(identity-api): PATCH + DELETE /v1/users/me routes"
```

(If a sibling test file was created, add it instead/additionally.)

### 5 — PR

- [ ] **Step 8:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-patch-delete-me
gh pr create --base main \
  --title "feat(identity-api): PATCH + DELETE /v1/users/me" \
  --body "$(cat <<'BODY'
## Summary
- Adds `PATCH /v1/users/me` — auth-gated; updates the user's display name via `UpdateMeUseCase` and returns the refreshed profile. `InvalidDisplayName` maps to 400 `invalid_display_name`.
- Adds `DELETE /v1/users/me` — auth-gated; calls `DeleteUserUseCase` (revokes sessions, broadcasts user-deleted event), clears the `__Host-ws_session` cookie, returns 204.
- Adds `Wiring.updateMeOrNull` + `Wiring.deleteUserOrNull` peek accessors matching the existing pattern.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers PATCH (no cookie 401, invalid body 400, invalid display name 400, valid 200) and DELETE (no cookie 401, valid 204 + cookie cleared + sessions revoked).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 48 chars.

---

## Future plans

- **4h** — `POST /v1/users/me/providers/{provider}/link` + linking-mode dispatch in the existing Google/Apple callback routes (when the persisted `AuthAttempt` has `linkToUserId` set, route into `CompleteProviderLinkUseCase` instead of `CompleteOidcLoginUseCase`).
- **Phase 4.5** — Helm chart + ingress for `auth.wordsparrow.io` + Cloudflare DNS + Google/Apple OAuth client registration + `kubectl create secret` bootstrap + `docs/deploy.md` update.
- **Phase 5** — Frontend.
- **Phase 6** — grid/game gating + production `UserDeletedBroadcaster` HTTP fan-out adapter.
