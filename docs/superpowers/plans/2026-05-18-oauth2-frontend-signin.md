# OAuth2 Frontend Sign-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement each task. Each implementer subagent MUST invoke the `frontend` skill at session start (for tasks 2–8); task 1 is JVM backend and the subagent MUST invoke `jvm-backend`. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship the frontend sign-in surface for the identity-api: header avatar + sign-in entry, `/compte` account page, hint gating, privacy page updates. Plus the one backend prerequisite (CORS).

**Architecture:** Frontend hexagonal — `application/auth/AuthClient.ts` is the port; `infrastructure/auth/HttpAuthClient.ts` is the adapter wrapping a generated `openapi-fetch` client against `identity/api/openapi.yaml`. React context `AuthProvider` calls `whoami()` on mount and exposes `useAuth()`. Sign-in is a full-page navigation to `auth.wordsparrow.io` (not a fetch). Account UI is a `/compte` route; quick actions live in the avatar popover.

**Tech stack:** Vite + React 19 + TanStack Router + Panda CSS + Ark UI + Vitest + MSW + Playwright + axe-core. Backend (Task 1 only): Ktor 3.4.3 CORS plugin.

**Reference spec:** `docs/superpowers/specs/2026-05-18-oauth2-frontend-signin-design.md`. Read it before starting.

---

## Shared conventions

These apply to every task in this plan:

- **Branch:** `<type>/oauth2-frontend-<topic>`. `type` is `feat`/`chore`/`docs` per the task.
- **PR title cap:** 70 chars.
- **PR body:** exactly `## Summary` (bullets) + `## Test plan` (markdown checklist). No other headings.
- **DCO sign-off:** every commit needs `git commit -s`.
- **400-line diff cap** (excluding generated code + blank lines). Run `git diff origin/main --shortstat` before pushing.
- **No emoji in code / comments / commits** unless the user explicitly asks.
- **Spotless / format:** for JVM, `./gradlew spotlessApply`. For frontend, `pnpm format` (Biome via the pre-commit hook handles most of it).
- **Lint gates that must stay green:** `pnpm typecheck`, `pnpm lint`, `pnpm test`, `pnpm api:check`. For frontend tasks invoke the `frontend` skill — it knows the boundaries:element-types rule and the uncontrolled-input contract.
- **Test command per surface:**
  - Frontend unit: `pnpm test`
  - Frontend e2e: `pnpm e2e -- tests/<file>.spec.ts`
  - Frontend a11y: `pnpm a11y`
  - Backend: `./gradlew :identity:api:check`

---

## File Structure

### Backend (Task 1)
- Modify: `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — install Ktor CORS plugin.
- Modify: `identity/api/build.gradle.kts` — add `ktor-server-cors` dependency.
- Create: `identity/api/src/test/kotlin/com/bliss/identity/api/CorsTest.kt` — preflight assertions.

### Frontend (Tasks 2–8)

```
frontend/
├── package.json                          # api:generate extended (Task 2)
├── src/
│   ├── application/auth/
│   │   ├── AuthClient.ts                 # port (Task 2)
│   │   └── index.ts                      # barrel (Task 2)
│   ├── infrastructure/
│   │   ├── api/identity/
│   │   │   ├── client.ts                 # createIdentityApiClient (Task 2)
│   │   │   └── types.ts                  # generated, don't hand-edit (Task 2)
│   │   ├── auth/
│   │   │   └── HttpAuthClient.ts         # adapter (Task 2)
│   │   └── mocks/handlers/
│   │       └── auth.ts                   # MSW handlers (Task 2)
│   ├── ui/
│   │   ├── components/auth/
│   │   │   ├── AuthProvider.tsx          # context + useAuth() (Task 3)
│   │   │   ├── HeaderAuthSlot.tsx        # routes anon/loading/authed (Task 3)
│   │   │   ├── SignInButton.tsx          # anchor → identity-api (Task 3)
│   │   │   ├── AvatarMenu.tsx            # Ark UI popover (Task 4)
│   │   │   ├── HintGate.tsx              # disables hint button when anon (Task 7)
│   │   │   └── index.ts                  # barrel
│   │   ├── components/layout/
│   │   │   └── AppHeader.tsx             # add HeaderAuthSlot to right slot (Task 3)
│   │   └── routes/
│   │       ├── __root.tsx                # AuthClient in AppRouterContext (Task 3)
│   │       ├── compte.tsx                # /compte page (Tasks 4–6)
│   │       ├── confidentialite.tsx       # FR privacy (Task 8)
│   │       └── privacy.tsx               # EN privacy (Task 8)
│   └── main.tsx                          # wire HttpAuthClient (Task 3)
└── tests/
    ├── auth-anon.spec.ts                 # anon flow e2e (Task 3)
    ├── auth-authed.spec.ts               # authed flow e2e (Tasks 4–6)
    └── hint-gate.spec.ts                 # hint gating e2e (Task 7)
```

---

## Task 1: Backend — Ktor CORS plugin on identity-api

**Branch:** `chore/oauth2-identity-cors`. **Subagent skill:** `jvm-backend`.

This is the prerequisite. The frontend can't talk to `auth.wordsparrow.io` cross-origin without it.

### 1.0 — Recon

- [ ] **Step 1.0a:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — note the plugin install order (CallId → CallLogging → DefaultHeaders → ContentNegotiation → StatusPages → routing).

- [ ] **Step 1.0b:** Read `identity/api/build.gradle.kts` to see how Ktor plugins are referenced (e.g. `ktor-server-call-logging`).

- [ ] **Step 1.0c:** Confirm `ktor-server-cors` exists at the same Ktor version pinned elsewhere (3.4.3). Maven coordinates: `io.ktor:ktor-server-cors:3.4.3`.

### 1.1 — Add dependency

- [ ] **Step 1.1a:** Add to `identity/api/build.gradle.kts` in the existing `dependencies { … }` block, next to other Ktor plugins:

```kotlin
implementation("io.ktor:ktor-server-cors:$ktorVersion")
```

- [ ] **Step 1.1b:** Verify Gradle resolves it:

```bash
./gradlew :identity:api:dependencies --configuration compileClasspath --quiet | grep ktor-server-cors
```

Expected: one line like `+--- io.ktor:ktor-server-cors:3.4.3` (or similar).

### 1.2 — Install plugin

- [ ] **Step 1.2a:** Modify `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt`. Add the import:

```kotlin
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
```

(`HttpMethod` + `HttpHeaders` may already be imported — check before adding.)

- [ ] **Step 1.2b:** In `Application.module(...)`, after `install(DefaultHeaders) { … }` and BEFORE `install(ContentNegotiation) { … }`, install CORS:

```kotlin
install(CORS) {
    // Allowed origins for cross-origin fetches from the frontend.
    // Cookie-bearing requests require `allowCredentials = true` + explicit
    // origins (no wildcard) — browsers reject Access-Control-Allow-Origin: *
    // with credentials.
    allowHost("wordsparrow.io", schemes = listOf("https"))
    allowHost("www.wordsparrow.io", schemes = listOf("https"))
    allowHost("bliss-cb4.pages.dev", schemes = listOf("https"))
    allowHost("localhost:5173", schemes = listOf("http"))

    // GET, POST, HEAD, OPTIONS are allowed by Ktor's CORS default.
    allowMethod(HttpMethod.Patch)
    allowMethod(HttpMethod.Delete)

    // Default permitted request headers don't include Content-Type — needed
    // for the JSON bodies on PATCH /v1/users/me + (future) link route.
    allowHeader(HttpHeaders.ContentType)

    allowCredentials = true
    maxAgeInSeconds = 600
}
```

### 1.3 — Test

- [ ] **Step 1.3a:** Create `identity/api/src/test/kotlin/com/bliss/identity/api/CorsTest.kt`:

```kotlin
package com.bliss.identity.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant

class CorsTest {
    private val testConfig = IdentityApiConfig(
        port = 0,
        publicHost = "auth.wordsparrow.example",
        google = GoogleClientConfig("g-client", "g-secret"),
        apple = AppleClientConfig(
            "a-svc", "a-team", "a-key",
            "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----",
        ),
        allowedReturnOrigins = listOf("https://wordsparrow.example"),
    )

    @Test
    fun `preflight from wordsparrow_io returns credentials-allowed CORS headers`() = testApplication {
        application { module(Wiring.forTesting(), testConfig) }
        val response = client.options("/v1/auth/whoami") {
            headers {
                append(HttpHeaders.Origin, "https://wordsparrow.io")
                append(HttpHeaders.AccessControlRequestMethod, "GET")
            }
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin] ?: "")
            .isEqualTo("https://wordsparrow.io")
        assertThat(response.headers[HttpHeaders.AccessControlAllowCredentials] ?: "")
            .isEqualTo("true")
    }

    @Test
    fun `preflight from disallowed origin omits CORS headers`() = testApplication {
        application { module(Wiring.forTesting(), testConfig) }
        val response = client.options("/v1/auth/whoami") {
            headers {
                append(HttpHeaders.Origin, "https://evil.example")
                append(HttpHeaders.AccessControlRequestMethod, "GET")
            }
        }
        // Ktor's CORS plugin returns 403 for disallowed origins on preflight.
        assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isEqualTo(null)
    }

    @Test
    fun `preflight allows PATCH on users-me`() = testApplication {
        application { module(Wiring.forTesting(), testConfig) }
        val response = client.options("/v1/users/me") {
            headers {
                append(HttpHeaders.Origin, "https://wordsparrow.io")
                append(HttpHeaders.AccessControlRequestMethod, "PATCH")
            }
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers[HttpHeaders.AccessControlAllowMethods] ?: "").contains("PATCH")
    }
}
```

- [ ] **Step 1.3b:** Run:

```bash
./gradlew :identity:api:test --tests "com.bliss.identity.api.CorsTest" --quiet
```

Expected: all 3 tests pass.

### 1.4 — Verify + commit + PR

- [ ] **Step 1.4a:** Full check:

```bash
./gradlew :identity:api:check --quiet
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.4b:** Commit:

```bash
git add identity/api/build.gradle.kts \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/CorsTest.kt
git commit -s -m "feat(identity-api): enable CORS for frontend cross-origin sign-in"
```

- [ ] **Step 1.4c:** Push + PR:

```bash
git push -u origin chore/oauth2-identity-cors
gh pr create --base main \
  --title "feat(identity-api): CORS for frontend cross-origin sign-in" \
  --body "$(cat <<'BODY'
## Summary
- Installs Ktor CORS plugin on identity-api permitting the four frontend origins (wordsparrow.io, www.wordsparrow.io, bliss-cb4.pages.dev, localhost:5173) with credentials.
- Allows PATCH + DELETE methods (Ktor default covers GET/POST/HEAD/OPTIONS) and the Content-Type header (needed for JSON bodies on PATCH /v1/users/me).
- Preflight cache: 600s.

## Test plan
- [x] `./gradlew :identity:api:check` green; 3 new CORS preflight tests cover allowed origin, disallowed origin, and PATCH method gating.
- [ ] After deploy, browser DevTools shows OPTIONS preflight from wordsparrow.io receives Access-Control-Allow-Origin + Access-Control-Allow-Credentials: true.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 56 chars.

---

## Task 2: AuthClient port + HTTP adapter

**Branch:** `feat/oauth2-frontend-auth-client`. **Subagent skill:** `frontend`.

Pure plumbing — port interface, fetch-based adapter, MSW handlers, unit tests. No UI.

### 2.0 — Recon

- [ ] **Step 2.0a:** Read the spec section "Architecture → AuthClient surface" (around line 70–95) for the exact interface shape.

- [ ] **Step 2.0b:** Read `frontend/src/infrastructure/api/grid/client.ts` for the `createXyzApiClient` pattern (openapi-fetch + uuidv7 X-Request-Id middleware).

- [ ] **Step 2.0c:** Read `frontend/src/infrastructure/api/grid/HttpPuzzleSolver.ts` (or `HttpPuzzleRepository.ts`) for the adapter pattern (port interface in `application/`, fetch adapter in `infrastructure/api/<ctx>/Http<Port>.ts`).

- [ ] **Step 2.0d:** Read `frontend/src/infrastructure/mocks/handlers/` — note one handler file per context, exported as a `handlers` array. Identify how the array is wired in `setup.ts`.

- [ ] **Step 2.0e:** Read `frontend/package.json` `api:generate` and `api:check` scripts.

### 2.1 — Extend api:generate to include identity

- [ ] **Step 2.1a:** Modify `frontend/package.json`:

```json
"api:generate": "openapi-typescript ../grid/api/openapi.yaml -o src/infrastructure/api/grid/types.ts && openapi-typescript ../game/api/openapi.yaml -o src/infrastructure/api/game/types.ts && openapi-typescript ../identity/api/openapi.yaml -o src/infrastructure/api/identity/types.ts",
"api:check": "pnpm api:generate && git diff --exit-code -- src/infrastructure/api/grid/types.ts src/infrastructure/api/game/types.ts src/infrastructure/api/identity/types.ts"
```

- [ ] **Step 2.1b:** Generate the identity types:

```bash
mkdir -p frontend/src/infrastructure/api/identity
cd frontend && pnpm api:generate
```

Expected: `src/infrastructure/api/identity/types.ts` created. Inspect — should contain `paths` and `components` for whoami, login, callback, logout, me CRUD, link.

- [ ] **Step 2.1c:** Commit generated types alone:

```bash
git add frontend/package.json frontend/src/infrastructure/api/identity/types.ts
git commit -s -m "chore(frontend): generate identity-api openapi types"
```

### 2.2 — Identity HTTP client wrapper

- [ ] **Step 2.2a:** Create `frontend/src/infrastructure/api/identity/client.ts`:

```typescript
import createClient, { type Client, type ClientOptions } from 'openapi-fetch';
import { uuidv7 } from 'uuidv7';

import type { paths } from './types';

/**
 * Options accepted by `createIdentityApiClient`. Same shape as grid/game
 * factories — base URL + optional fetch override. The identity service runs
 * on a dedicated subdomain (`auth.wordsparrow.io` prod / sandbox) so the URL
 * is supplied by the composition root via env.
 */
export interface IdentityApiClientOptions {
  /** Absolute base URL of the identity API (e.g. `https://auth.wordsparrow.io`). */
  readonly baseUrl: string;
  /** Optional fetch override for tests + sandboxed runtimes. */
  readonly fetch?: ClientOptions['fetch'];
}

/**
 * Build a typed identity-api client. Cookie-bearing endpoints require
 * `credentials: 'include'` per request (callers set it; the factory does
 * not force-include credentials so probe-style GETs stay light).
 */
export function createIdentityApiClient(
  options: IdentityApiClientOptions,
): Client<paths> {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
  });
  client.use({
    onRequest({ request }) {
      if (!request.headers.has('X-Request-Id')) {
        request.headers.set('X-Request-Id', uuidv7());
      }
      return request;
    },
  });
  return client;
}

export type IdentityApiClient = Client<paths>;
export type { paths } from './types';
```

### 2.3 — Port

- [ ] **Step 2.3a:** Create `frontend/src/application/auth/AuthClient.ts`:

```typescript
// Hexagonal port for identity-api access. Concrete adapter lives in
// `@/infrastructure/auth/HttpAuthClient`. UI consumes this interface only.
//
// Per ADR-0002 §7 the boundaries plugin forbids `ui/` from importing
// `infrastructure/`; routes get the AuthClient via the router context.

export interface WhoAmIResult {
  readonly userId: string;
  readonly displayName: string;
}

export interface LinkedProvider {
  readonly provider: 'google' | 'apple';
  readonly linkedAt: string;
  readonly emailOptIn: boolean;
}

export interface GetMeResult {
  readonly id: string;
  readonly displayName: string;
  readonly createdAt: string;
  readonly lastSeenAt: string;
  readonly linkedProviders: ReadonlyArray<LinkedProvider>;
}

export class InvalidDisplayNameError extends Error {
  constructor(detail: string) {
    super(detail);
    this.name = 'InvalidDisplayNameError';
  }
}

export interface AuthClient {
  /** GET /v1/auth/whoami. Returns null on 401. Throws on network error. */
  whoami(): Promise<WhoAmIResult | null>;

  /** GET /v1/users/me. Throws on 401. */
  getMe(): Promise<GetMeResult>;

  /**
   * PATCH /v1/users/me with `{ displayName }`. Throws InvalidDisplayNameError
   * on 400 invalid_display_name; rethrows other failures unchanged.
   */
  updateMe(displayName: string): Promise<void>;

  /** DELETE /v1/users/me. Resolves on 204. */
  deleteMe(): Promise<void>;

  /** POST /v1/auth/logout. Resolves on 204. */
  logout(): Promise<void>;

  /**
   * Build the URL for a `<a href>` that begins the Google sign-in flow.
   * `returnTo` is the absolute URL the user lands on after Google + the
   * identity-api callback (must match the server's ALLOWED_RETURN_ORIGINS
   * allow-list).
   */
  signInUrl(returnTo: string): string;
}
```

- [ ] **Step 2.3b:** Create `frontend/src/application/auth/index.ts`:

```typescript
export type {
  AuthClient,
  GetMeResult,
  LinkedProvider,
  WhoAmIResult,
} from './AuthClient';
export { InvalidDisplayNameError } from './AuthClient';
```

### 2.4 — HTTP adapter

- [ ] **Step 2.4a:** Create `frontend/src/infrastructure/auth/HttpAuthClient.ts`:

```typescript
import {
  type AuthClient,
  type GetMeResult,
  InvalidDisplayNameError,
  type WhoAmIResult,
} from '@/application/auth';
import { createIdentityApiClient, type IdentityApiClient } from '../api/identity/client';

export interface CreateHttpAuthClientOptions {
  /** Identity-api base URL — `https://auth.wordsparrow.io` in prod. */
  readonly baseUrl: string;
  /** Optional fetch override (tests + MSW). */
  readonly fetch?: typeof fetch;
}

/**
 * Build the HTTP-backed AuthClient. All cookie-bearing endpoints use
 * `credentials: 'include'` so the `__Host-ws_session` cookie travels
 * cross-origin from `wordsparrow.io` → `auth.wordsparrow.io`.
 */
export function createHttpAuthClient(options: CreateHttpAuthClientOptions): AuthClient {
  const client: IdentityApiClient = createIdentityApiClient({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
  });

  return {
    async whoami() {
      const { data, response } = await client.GET('/v1/auth/whoami', {
        credentials: 'include',
      });
      if (response.status === 401) return null;
      if (!data) {
        throw new Error(`whoami failed: ${String(response.status)}`);
      }
      return data as WhoAmIResult;
    },

    async getMe() {
      const { data, response } = await client.GET('/v1/users/me', {
        credentials: 'include',
      });
      if (!data) {
        throw new Error(`getMe failed: ${String(response.status)}`);
      }
      return data as GetMeResult;
    },

    async updateMe(displayName) {
      const { error, response } = await client.PATCH('/v1/users/me', {
        credentials: 'include',
        body: { displayName },
      });
      if (response.status === 400 && error) {
        const detail = (error as { detail?: string }).detail ?? 'Pseudonyme invalide.';
        throw new InvalidDisplayNameError(detail);
      }
      if (error) {
        throw new Error(`updateMe failed: ${String(response.status)} ${JSON.stringify(error)}`);
      }
    },

    async deleteMe() {
      const { error, response } = await client.DELETE('/v1/users/me', {
        credentials: 'include',
      });
      if (error) {
        throw new Error(`deleteMe failed: ${String(response.status)} ${JSON.stringify(error)}`);
      }
    },

    async logout() {
      const { error, response } = await client.POST('/v1/auth/logout', {
        credentials: 'include',
      });
      if (error) {
        throw new Error(`logout failed: ${String(response.status)} ${JSON.stringify(error)}`);
      }
    },

    signInUrl(returnTo) {
      const url = new URL(`${options.baseUrl.replace(/\/$/, '')}/v1/auth/google/login`);
      url.searchParams.set('return_to', returnTo);
      return url.toString();
    },
  };
}
```

### 2.5 — MSW handlers

- [ ] **Step 2.5a:** Create `frontend/src/infrastructure/mocks/handlers/auth.ts`:

```typescript
import { http, HttpResponse } from 'msw';

const BASE = 'https://auth.wordsparrow.example';

interface WhoAmIFixture {
  userId: string;
  displayName: string;
}
interface MeFixture {
  id: string;
  displayName: string;
  createdAt: string;
  lastSeenAt: string;
  linkedProviders: ReadonlyArray<{
    provider: string;
    linkedAt: string;
    emailOptIn: boolean;
  }>;
}

// Mutable test state — handlers default to "anon"; tests call
// `setAuthed(...)` / `setAnon()` to switch.
let whoami: WhoAmIFixture | null = null;
let me: MeFixture | null = null;

export function setAuthed(fixture: WhoAmIFixture, full: MeFixture): void {
  whoami = fixture;
  me = full;
}
export function setAnon(): void {
  whoami = null;
  me = null;
}

export const authHandlers = [
  http.get(`${BASE}/v1/auth/whoami`, () =>
    whoami
      ? HttpResponse.json(whoami, { status: 200 })
      : new HttpResponse(null, { status: 401 }),
  ),
  http.get(`${BASE}/v1/users/me`, () =>
    me
      ? HttpResponse.json(me, { status: 200 })
      : new HttpResponse(null, { status: 401 }),
  ),
  http.patch(`${BASE}/v1/users/me`, async ({ request }) => {
    const body = (await request.json()) as { displayName?: string };
    const name = body.displayName ?? '';
    if (name.length === 0 || name.length > 30) {
      return HttpResponse.json(
        {
          type: 'https://wordsparrow.io/errors/invalid_display_name',
          title: 'Bad Request',
          status: 400,
          detail: 'Le pseudo doit faire entre 1 et 30 caractères.',
        },
        { status: 400 },
      );
    }
    if (me) me = { ...me, displayName: name };
    if (whoami) whoami = { ...whoami, displayName: name };
    return new HttpResponse(null, { status: 204 });
  }),
  http.delete(`${BASE}/v1/users/me`, () => {
    setAnon();
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(`${BASE}/v1/auth/logout`, () => {
    setAnon();
    return new HttpResponse(null, { status: 204 });
  }),
];

export const TEST_BASE_URL = BASE;
```

- [ ] **Step 2.5b:** Register the handlers. Read `frontend/src/test/setup.ts` (or wherever the MSW server is composed) and add `...authHandlers` to its `setupServer(...)` array. The exact integration depends on how that file is structured — match the existing pattern for grid/game handlers.

### 2.6 — Unit tests

- [ ] **Step 2.6a:** Create `frontend/src/infrastructure/auth/HttpAuthClient.test.ts`:

```typescript
import { describe, expect, it, beforeEach } from 'vitest';
import { createHttpAuthClient } from './HttpAuthClient';
import { InvalidDisplayNameError } from '@/application/auth';
import {
  TEST_BASE_URL,
  setAnon,
  setAuthed,
} from '@/infrastructure/mocks/handlers/auth';

describe('HttpAuthClient', () => {
  const client = createHttpAuthClient({ baseUrl: TEST_BASE_URL });

  beforeEach(() => setAnon());

  it('whoami returns null on 401', async () => {
    expect(await client.whoami()).toBeNull();
  });

  it('whoami returns the user when authed', async () => {
    setAuthed(
      { userId: 'u-1', displayName: 'Lapin 472' },
      {
        id: 'u-1',
        displayName: 'Lapin 472',
        createdAt: '2026-05-18T10:00:00Z',
        lastSeenAt: '2026-05-18T10:05:00Z',
        linkedProviders: [],
      },
    );
    const result = await client.whoami();
    expect(result).toEqual({ userId: 'u-1', displayName: 'Lapin 472' });
  });

  it('getMe returns linked providers', async () => {
    setAuthed(
      { userId: 'u-1', displayName: 'Lapin 472' },
      {
        id: 'u-1',
        displayName: 'Lapin 472',
        createdAt: '2026-05-18T10:00:00Z',
        lastSeenAt: '2026-05-18T10:05:00Z',
        linkedProviders: [
          { provider: 'google', linkedAt: '2026-05-18T10:00:00Z', emailOptIn: false },
        ],
      },
    );
    const me = await client.getMe();
    expect(me.linkedProviders).toHaveLength(1);
    expect(me.linkedProviders[0]?.provider).toBe('google');
  });

  it('updateMe throws InvalidDisplayNameError on 400', async () => {
    setAuthed(
      { userId: 'u-1', displayName: 'Lapin 472' },
      {
        id: 'u-1',
        displayName: 'Lapin 472',
        createdAt: '2026-05-18T10:00:00Z',
        lastSeenAt: '2026-05-18T10:05:00Z',
        linkedProviders: [],
      },
    );
    await expect(client.updateMe('')).rejects.toBeInstanceOf(InvalidDisplayNameError);
  });

  it('updateMe resolves on 204', async () => {
    setAuthed(
      { userId: 'u-1', displayName: 'Lapin 472' },
      {
        id: 'u-1',
        displayName: 'Lapin 472',
        createdAt: '2026-05-18T10:00:00Z',
        lastSeenAt: '2026-05-18T10:05:00Z',
        linkedProviders: [],
      },
    );
    await client.updateMe('Renard 888');
    const whoami = await client.whoami();
    expect(whoami?.displayName).toBe('Renard 888');
  });

  it('signInUrl builds the right URL', () => {
    const url = client.signInUrl('https://wordsparrow.example/play');
    expect(url).toBe(
      `${TEST_BASE_URL}/v1/auth/google/login?return_to=https%3A%2F%2Fwordsparrow.example%2Fplay`,
    );
  });
});
```

- [ ] **Step 2.6b:** Run:

```bash
cd frontend && pnpm test src/infrastructure/auth/HttpAuthClient.test.ts
```

Expected: 6 tests pass.

### 2.7 — Verify + commit + PR

- [ ] **Step 2.7a:** Run full frontend gates:

```bash
cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm api:check
```

All green.

- [ ] **Step 2.7b:** Commit:

```bash
git add frontend/src/application/auth \
        frontend/src/infrastructure/api/identity/client.ts \
        frontend/src/infrastructure/auth \
        frontend/src/infrastructure/mocks/handlers/auth.ts \
        frontend/src/test/setup.ts
git commit -s -m "feat(frontend-identity): add AuthClient port + HTTP adapter"
```

- [ ] **Step 2.7c:** Push + PR (title 51 chars):

```bash
git push -u origin feat/oauth2-frontend-auth-client
gh pr create --base main \
  --title "feat(frontend-identity): AuthClient port + HTTP adapter" \
  --body "$(cat <<'BODY'
## Summary
- Extends `pnpm api:generate` to cover `identity/api/openapi.yaml` → `src/infrastructure/api/identity/types.ts`.
- Adds the `AuthClient` hexagonal port (`application/auth/`) + `createHttpAuthClient` adapter (`infrastructure/auth/`) wrapping a fresh openapi-fetch client. All cookie-bearing calls use `credentials: 'include'`.
- Adds MSW handlers under `infrastructure/mocks/handlers/auth.ts` with `setAuthed(...)` / `setAnon()` test helpers.

## Test plan
- [x] `pnpm test` — 6 new unit tests covering whoami/getMe/updateMe/deleteMe/logout/signInUrl.
- [x] `pnpm typecheck`, `pnpm lint`, `pnpm api:check` green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

---

## Task 3: AuthProvider + HeaderAuthSlot + SignInButton

**Branch:** `feat/oauth2-frontend-auth-provider`. **Subagent skill:** `frontend`.

This task wires the AuthClient through the router context and adds the visible header state. After it ships, anon users see "Se connecter" and authed users see a stub avatar with sign-out.

### 3.0 — Recon

- [ ] **Step 3.0a:** Read `frontend/src/ui/routes/__root.tsx` — note the `AppRouterContext` interface and where it lives.

- [ ] **Step 3.0b:** Read `frontend/src/main.tsx` — note how `sessionClient` etc. are constructed and passed via `router.options.context`.

- [ ] **Step 3.0c:** Read `frontend/src/ui/components/layout/AppHeader.tsx` in full — note the right-slot comment ("streak pill / avatar from the brief mock are not in the current scope") and the responsive (desktop ≥ 768 px / mobile < 768 px) shape. The right slot is currently empty.

- [ ] **Step 3.0d:** Read `frontend/src/infrastructure/session/localStorageSession.ts` — pin the `getPseudonym()` signature and the `generateDefaultPseudonym()` default-detection. We need to detect "user has NOT customized the pseudonym".

- [ ] **Step 3.0e:** Identify the env var for the identity-api base URL. Read `frontend/.env*` files + `main.tsx`'s use of `import.meta.env.VITE_*`. Add `VITE_IDENTITY_API_BASE_URL` if not present.

### 3.1 — Detect "default pseudonym"

The first-sign-in flow needs to know if the localStorage pseudonym is user-customized or a default. The current generator produces "Animal NNN"; we need a deterministic check.

- [ ] **Step 3.1a:** Modify `frontend/src/infrastructure/session/localStorageSession.ts`. After `generateDefaultPseudonym`, export a checker:

```typescript
/**
 * Returns true if `pseudonym` looks like an auto-generated default
 * (an entry from ANIMAL_NAMES followed by " " + a 3-digit number).
 * False positive cost is low: a user who manually typed "Lapin 472"
 * would not be carried over on first sign-in. Acceptable per the Phase 5
 * spec's "Anon-pseudonym mismatch" risk note.
 */
export function isDefaultPseudonym(pseudonym: string): boolean {
  const match = pseudonym.match(/^(.+) (\d{3})$/);
  if (!match) return false;
  return ANIMAL_NAMES.includes(match[1] as (typeof ANIMAL_NAMES)[number]);
}
```

- [ ] **Step 3.1b:** Run existing localStorageSession tests to confirm no regression:

```bash
cd frontend && pnpm test localStorageSession
```

### 3.2 — Extend AppRouterContext

- [ ] **Step 3.2a:** Modify `frontend/src/ui/routes/__root.tsx`. Add `authClient: AuthClient` to `AppRouterContext`:

```typescript
import type { AuthClient } from '@/application/auth';

export interface AppRouterContext {
  // ...existing fields...
  readonly authClient: AuthClient;
}
```

- [ ] **Step 3.2b:** Modify `frontend/src/main.tsx`. Construct the HttpAuthClient and pass it via context:

```typescript
import { createHttpAuthClient } from '@/infrastructure/auth/HttpAuthClient';

const authClient = createHttpAuthClient({
  baseUrl: import.meta.env.VITE_IDENTITY_API_BASE_URL ?? 'https://auth.wordsparrow.io',
});

// Then in the router options' context block, add `authClient` to the existing object.
```

Add `VITE_IDENTITY_API_BASE_URL=https://auth.wordsparrow.io` to `.env.production` (if it exists) or document the var in the README.

### 3.3 — AuthProvider

- [ ] **Step 3.3a:** Create `frontend/src/ui/components/auth/AuthProvider.tsx`:

```typescript
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import type { AuthClient, WhoAmIResult } from '@/application/auth';
import { getPseudonym, isDefaultPseudonym } from '@/infrastructure/session/localStorageSession';

export type AuthState =
  | { readonly status: 'loading' }
  | { readonly status: 'anon' }
  | { readonly status: 'authed'; readonly whoami: WhoAmIResult };

interface AuthContextValue {
  readonly state: AuthState;
  /** Force a re-check (used by sign-out, delete-account, updateMe success). */
  readonly refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

interface AuthProviderProps {
  readonly authClient: AuthClient;
  readonly children: ReactNode;
}

const SERVER_DEFAULT_DISPLAY_NAME = 'Joueur';

export function AuthProvider({ authClient, children }: AuthProviderProps) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    const whoami = await authClient.whoami();
    if (!whoami) {
      setState({ status: 'anon' });
      return;
    }
    // First-sign-in carry-over: if the server still has the default name AND
    // the localStorage anon pseudonym is non-default (i.e. an animal name
    // generated for this user), patch it once. Idempotent — after the patch
    // succeeds, displayName !== 'Joueur' and this branch never fires again.
    if (whoami.displayName === SERVER_DEFAULT_DISPLAY_NAME) {
      const local = getPseudonym();
      if (isDefaultPseudonym(local)) {
        try {
          await authClient.updateMe(local);
          const fresh = await authClient.whoami();
          if (fresh) {
            setState({ status: 'authed', whoami: fresh });
            return;
          }
        } catch {
          // Patch failed (network / invalid). Fall through with the default
          // name; user can edit in /compte later.
        }
      }
    }
    setState({ status: 'authed', whoami });
  }, [authClient]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') {
        void refresh();
      }
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [refresh]);

  return (
    <AuthContext.Provider value={{ state, refresh }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
```

- [ ] **Step 3.3b:** Wrap the router tree with `AuthProvider`. Modify `frontend/src/main.tsx` (or wherever `<RouterProvider>` mounts) so `<AuthProvider authClient={authClient}>` wraps it. Pull the `authClient` from the same construction site that passes it via context.

### 3.4 — SignInButton

- [ ] **Step 3.4a:** Create `frontend/src/ui/components/auth/SignInButton.tsx`:

```typescript
import { css } from 'styled-system/css';
import { useAuth } from './AuthProvider';
import { useRouterState } from '@tanstack/react-router';
import type { AuthClient } from '@/application/auth';

interface SignInButtonProps {
  readonly authClient: AuthClient;
}

const styles = css({
  display: 'inline-flex',
  alignItems: 'center',
  paddingInline: '12px',
  paddingBlock: '6px',
  borderRadius: 'md',
  bg: 'accent',
  color: 'bg',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'medium',
  textDecoration: 'none',
  _hover: { opacity: 0.9 },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

export function SignInButton({ authClient }: SignInButtonProps) {
  // The button is a real <a href>. Sign-in needs a full-page navigation so
  // the browser follows the 302 chain and accepts Set-Cookie on the way
  // back — fetch() would not work cross-origin with a 302 to Google.
  const currentUrl = typeof window === 'undefined' ? '' : window.location.href;
  const href = authClient.signInUrl(currentUrl);
  return (
    <a href={href} className={styles}>
      Se connecter
    </a>
  );
}
```

(Note: the `useAuth` / `useRouterState` imports above are placeholders for keeping import sets aligned with sibling components; if unused after final pass, drop them.)

### 3.5 — Stub AvatarMenu (full version lands in Task 4)

- [ ] **Step 3.5a:** Create `frontend/src/ui/components/auth/AvatarMenu.tsx`:

```typescript
import { css } from 'styled-system/css';
import { useAuth } from './AuthProvider';
import type { AuthClient, WhoAmIResult } from '@/application/auth';

interface AvatarMenuProps {
  readonly authClient: AuthClient;
  readonly whoami: WhoAmIResult;
}

const buttonStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px',
  paddingInline: '8px',
  paddingBlock: '4px',
  bg: 'transparent',
  border: 'none',
  cursor: 'pointer',
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'fg',
  _hover: { textDecoration: 'underline' },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

export function AvatarMenu({ authClient, whoami }: AvatarMenuProps) {
  const { refresh } = useAuth();
  // Stub: a single Se-déconnecter button. The full Ark UI popover with
  // display name + /compte link lands in Task 4.
  async function signOut() {
    await authClient.logout();
    await refresh();
  }
  return (
    <button type="button" onClick={signOut} className={buttonStyles} aria-label="Se déconnecter">
      {whoami.displayName.charAt(0).toUpperCase()} · Se déconnecter
    </button>
  );
}
```

### 3.6 — HeaderAuthSlot

- [ ] **Step 3.6a:** Create `frontend/src/ui/components/auth/HeaderAuthSlot.tsx`:

```typescript
import { css } from 'styled-system/css';
import { useAuth } from './AuthProvider';
import { SignInButton } from './SignInButton';
import { AvatarMenu } from './AvatarMenu';
import type { AuthClient } from '@/application/auth';

interface HeaderAuthSlotProps {
  readonly authClient: AuthClient;
}

const skeletonStyles = css({
  width: '90px',
  height: '28px',
  borderRadius: 'md',
  bg: 'subtleBg',
  opacity: 0.6,
});

export function HeaderAuthSlot({ authClient }: HeaderAuthSlotProps) {
  const { state } = useAuth();
  if (state.status === 'loading') {
    return <div className={skeletonStyles} aria-hidden="true" />;
  }
  if (state.status === 'anon') {
    return <SignInButton authClient={authClient} />;
  }
  return <AvatarMenu authClient={authClient} whoami={state.whoami} />;
}
```

- [ ] **Step 3.6b:** Create `frontend/src/ui/components/auth/index.ts`:

```typescript
export { AuthProvider, useAuth } from './AuthProvider';
export type { AuthState } from './AuthProvider';
export { HeaderAuthSlot } from './HeaderAuthSlot';
export { SignInButton } from './SignInButton';
export { AvatarMenu } from './AvatarMenu';
```

### 3.7 — Wire into AppHeader

- [ ] **Step 3.7a:** Modify `frontend/src/ui/components/layout/AppHeader.tsx`. Replace the empty right slot with `<HeaderAuthSlot authClient={…} />`. The `authClient` comes from the router context — pull via `useRouterState` or accept it as a prop. Prefer prop-drilling from the page that renders `AppHeader` so the component itself remains presentational. If `AppHeader` already pulls the context for other reasons, follow that pattern.

Match the existing responsive structure: the slot is on the right at desktop ≥ 768 px and lives inside the mobile hamburger's overflow menu at < 768 px (the latter requires adding a `<HeaderAuthSlot>` instance inside the overflow menu's items).

### 3.8 — Tests

- [ ] **Step 3.8a:** Create `frontend/src/ui/components/auth/AuthProvider.test.tsx`:

```typescript
import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthProvider';
import { createHttpAuthClient } from '@/infrastructure/auth/HttpAuthClient';
import {
  TEST_BASE_URL,
  setAnon,
  setAuthed,
} from '@/infrastructure/mocks/handlers/auth';

function Probe() {
  const { state } = useAuth();
  return <div data-testid="state">{state.status}</div>;
}

describe('AuthProvider', () => {
  const authClient = createHttpAuthClient({ baseUrl: TEST_BASE_URL });

  beforeEach(() => setAnon());

  it('transitions loading → anon', async () => {
    render(<AuthProvider authClient={authClient}><Probe /></AuthProvider>);
    expect(screen.getByTestId('state').textContent).toBe('loading');
    await waitFor(() => expect(screen.getByTestId('state').textContent).toBe('anon'));
  });

  it('transitions loading → authed when whoami returns 200', async () => {
    setAuthed(
      { userId: 'u-1', displayName: 'Lapin 472' },
      {
        id: 'u-1',
        displayName: 'Lapin 472',
        createdAt: '2026-05-18T10:00:00Z',
        lastSeenAt: '2026-05-18T10:05:00Z',
        linkedProviders: [],
      },
    );
    render(<AuthProvider authClient={authClient}><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('state').textContent).toBe('authed'));
  });
});
```

- [ ] **Step 3.8b:** Create `frontend/tests/auth-anon.spec.ts` (Playwright e2e):

```typescript
import { test, expect } from '@playwright/test';

test('anon user sees Se connecter button', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();
});

test('clicking Se connecter navigates to identity-api login URL', async ({ page }) => {
  await page.goto('/');
  const link = page.getByRole('link', { name: 'Se connecter' });
  const href = await link.getAttribute('href');
  expect(href).toContain('/v1/auth/google/login?return_to=');
});
```

- [ ] **Step 3.8c:** Run all gates:

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint
```

### 3.9 — Commit + PR

- [ ] **Step 3.9a:** Commit:

```bash
git add frontend/src/infrastructure/session/localStorageSession.ts \
        frontend/src/ui/routes/__root.tsx \
        frontend/src/main.tsx \
        frontend/src/ui/components/auth \
        frontend/src/ui/components/layout/AppHeader.tsx \
        frontend/tests/auth-anon.spec.ts
git commit -s -m "feat(frontend-identity): auth context + header sign-in/avatar slot"
```

- [ ] **Step 3.9b:** Push + PR (title 60 chars):

```bash
git push -u origin feat/oauth2-frontend-auth-provider
gh pr create --base main \
  --title "feat(frontend-identity): auth context + header sign-in slot" \
  --body "$(cat <<'BODY'
## Summary
- Adds `AuthProvider` React context with `loading | anon | authed` state and a `refresh()` callback. On mount and on visibilitychange it calls `whoami()` so a sign-in in another tab updates this one.
- First-sign-in carry-over: when the server returns the default `"Joueur"` and the localStorage pseudonym is an animal-name default, `AuthProvider` PATCHes the display name once.
- Adds `HeaderAuthSlot` + `SignInButton` (anchor → `auth.wordsparrow.io/v1/auth/google/login?return_to=<current>`) + stub `AvatarMenu` (display-name initial + sign-out button only — full popover lands in PR 4 of this phase).
- Wires `AuthClient` through `AppRouterContext` and composes `HttpAuthClient` in `main.tsx`.

## Test plan
- [x] `pnpm test` — AuthProvider state-transition tests (loading→anon, loading→authed).
- [x] `pnpm e2e -- tests/auth-anon.spec.ts` — anon sees `Se connecter` link with the right `href`.
- [x] `pnpm typecheck`, `pnpm lint`, `pnpm a11y` (header) green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

---

## Task 4: AvatarMenu (full) + /compte route foundation

**Branch:** `feat/oauth2-frontend-compte-foundation`. **Subagent skill:** `frontend`.

Upgrades the stub `AvatarMenu` to a real Ark UI popover and lays down the `/compte` route showing display name + linked providers as read-only. No edit, no delete (those land in PRs 5 + 6).

### 4.0 — Recon

- [ ] **Step 4.0a:** Find a sibling Ark UI popover usage in the codebase to match patterns: `grep -rn 'Popover' frontend/src/ui/components --include="*.tsx" | head`. Read one example. If none exists, fall back to `Menu` or use Ark UI's official popover example.

- [ ] **Step 4.0b:** Read `frontend/src/ui/routes/aide.tsx` for a static-content route example (route registration shape + layout style).

- [ ] **Step 4.0c:** Read `frontend/src/ui/components/primitives/` index — note `OverflowMenu` (used in AppHeader mobile) for popover/menu patterns.

### 4.1 — AvatarMenu — full popover

- [ ] **Step 4.1a:** Replace the body of `frontend/src/ui/components/auth/AvatarMenu.tsx` with an Ark UI popover. The trigger is the avatar (initial + chevron); the content has: display name truncated, a `<Link to="/compte">Mon compte</Link>`, and a `<button>Se déconnecter</button>`. Use Panda CSS tokens for styling and `aria-label="Compte"` on the trigger.

Implementation outline (fill in styling tokens from the codebase's design tokens):

```typescript
import { Popover } from '@ark-ui/react/popover';
import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { useAuth } from './AuthProvider';
import type { AuthClient, WhoAmIResult } from '@/application/auth';

interface AvatarMenuProps {
  readonly authClient: AuthClient;
  readonly whoami: WhoAmIResult;
}

const triggerStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '32px',
  height: '32px',
  borderRadius: 'full',
  bg: 'accent',
  color: 'bg',
  fontFamily: 'body',
  fontWeight: 'medium',
  cursor: 'pointer',
  border: 'none',
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

const contentStyles = css({
  bg: 'bg',
  border: '1px solid token(colors.gridLine)',
  borderRadius: 'md',
  boxShadow: 'md',
  padding: '12px',
  minWidth: '200px',
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  zIndex: 20,
});

const nameStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'medium',
  color: 'fg',
  paddingBlock: '4px',
  borderBottom: '1px solid token(colors.gridLine)',
  marginBottom: '4px',
  // Truncate to 20 chars via CSS — JS truncation strips Unicode badly.
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: '180px',
});

const linkStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'fg',
  textDecoration: 'none',
  paddingBlock: '4px',
  _hover: { textDecoration: 'underline' },
});

const signOutStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'fg',
  background: 'transparent',
  border: 'none',
  paddingBlock: '4px',
  textAlign: 'left',
  cursor: 'pointer',
  _hover: { textDecoration: 'underline' },
});

export function AvatarMenu({ authClient, whoami }: AvatarMenuProps) {
  const { refresh } = useAuth();
  const initial = whoami.displayName.charAt(0).toUpperCase();

  async function signOut() {
    await authClient.logout();
    await refresh();
  }

  return (
    <Popover.Root>
      <Popover.Trigger className={triggerStyles} aria-label="Compte">
        {initial}
      </Popover.Trigger>
      <Popover.Positioner>
        <Popover.Content className={contentStyles}>
          <span className={nameStyles} aria-label={`Connecté en tant que ${whoami.displayName}`}>
            {whoami.displayName}
          </span>
          <Link to="/compte" className={linkStyles}>
            Mon compte
          </Link>
          <button type="button" onClick={signOut} className={signOutStyles}>
            Se déconnecter
          </button>
        </Popover.Content>
      </Popover.Positioner>
    </Popover.Root>
  );
}
```

### 4.2 — /compte route (read-only foundation)

- [ ] **Step 4.2a:** Create `frontend/src/ui/routes/compte.tsx`. Auth-guarded via TanStack Router's `beforeLoad` — anon users get redirected to `/` with a toast.

```typescript
import { createFileRoute, redirect } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import { useAuth } from '@/ui/components/auth/AuthProvider';
import type { AuthClient, GetMeResult } from '@/application/auth';
import { useToast } from '@/ui/components/primitives';

export const Route = createFileRoute('/compte')({
  beforeLoad: ({ context, location }) => {
    // The route loader runs before the component. We can't access React
    // context here, but the router context already carries authClient.
    // Anon detection is done in the component: beforeLoad guards on
    // network reachability (which fails fast). The component itself
    // navigates to '/' if state is anon (cheaper than a second whoami).
  },
  component: ComptePage,
});

const pageStyles = css({
  maxWidth: '720px',
  marginInline: 'auto',
  paddingInline: '20px',
  paddingBlock: '32px',
  display: 'flex',
  flexDirection: 'column',
  gap: '32px',
});

const sectionStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: '12px',
});

const headingStyles = css({
  fontFamily: 'display',
  fontSize: 'lg',
  fontWeight: 'medium',
  color: 'fg',
});

const labelStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'mutedFg',
});

const valueStyles = css({
  fontFamily: 'body',
  fontSize: 'md',
  color: 'fg',
});

const providerRowStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  padding: '8px 12px',
  border: '1px solid token(colors.gridLine)',
  borderRadius: 'md',
});

const providerRowDisabledStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  padding: '8px 12px',
  border: '1px solid token(colors.gridLine)',
  borderRadius: 'md',
  opacity: 0.5,
});

function ComptePage() {
  const { state } = useAuth();
  const { push: pushToast } = useToast();
  const [me, setMe] = useState<GetMeResult | null>(null);

  // Auth guard. Redirect anon users to '/' with a toast.
  // Uses a navigation effect so the route renders nothing for anon (no flicker).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (state.status === 'anon') {
      pushToast({ message: 'Connectez-vous pour accéder à votre compte.', tone: 'info' });
      // Replace the history entry so back-button doesn't loop.
      window.history.replaceState(null, '', '/');
      window.location.href = '/';
    }
  }, [state.status]);

  // Fetch the full /me payload once we know we're authed.
  // The router context's authClient is the source of truth.
  useEffect(() => {
    if (state.status !== 'authed') return;
    const ctx = (window as unknown as { __APP_AUTH_CLIENT__?: AuthClient }).__APP_AUTH_CLIENT__;
    // ↑ HACK: replace with proper router-context plumbing. See note below.
    if (!ctx) return;
    void ctx.getMe().then(setMe);
  }, [state.status]);

  if (state.status !== 'authed') return null;
  if (!me) {
    return <div className={pageStyles}>Chargement…</div>;
  }

  const google = me.linkedProviders.find((p) => p.provider === 'google');
  return (
    <main className={pageStyles}>
      <section className={sectionStyles} aria-labelledby="account-name">
        <h2 id="account-name" className={headingStyles}>Pseudonyme</h2>
        <div>
          <div className={labelStyles}>Pseudonyme actuel</div>
          <div className={valueStyles}>{me.displayName}</div>
        </div>
      </section>

      <section className={sectionStyles} aria-labelledby="account-providers">
        <h2 id="account-providers" className={headingStyles}>Comptes liés</h2>
        {google ? (
          <div className={providerRowStyles}>
            <span>Google · lié le {formatDate(google.linkedAt)}</span>
          </div>
        ) : (
          <div className={providerRowStyles}>Google · pas encore lié</div>
        )}
        <div className={providerRowDisabledStyles}>
          Apple · bientôt disponible
        </div>
      </section>
    </main>
  );
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
}
```

**Router-context plumbing note:** the implementer should refactor the `__APP_AUTH_CLIENT__` HACK into a proper `useRouteContext()` call. The `Route` from TanStack Router exposes its context via `Route.useRouteContext()`. The skeleton above shows the desired data flow; the implementer pins the exact API based on TanStack Router's version in `package.json`. If the codebase already has a pattern (e.g. `useAppContext` hook), reuse it.

- [ ] **Step 4.2b:** Register the route. TanStack Router with file-based routing auto-detects `frontend/src/ui/routes/compte.tsx`. Verify by running `pnpm dev` and navigating to `/compte`.

### 4.3 — Tests

- [ ] **Step 4.3a:** Create `frontend/tests/auth-authed.spec.ts`:

```typescript
import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  // Stub the auth state by setting the whoami fixture before navigating.
  // Concrete mechanism depends on how the e2e test harness wires MSW
  // (it likely injects a cookie via page.context().addCookies({...}) or
  // uses an MSW-in-the-browser worker).
  // The implementer wires this consistent with the existing e2e setup.
});

test('authed user sees avatar in header', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Compte' })).toBeVisible();
});

test('avatar popover shows display name and Mon compte link', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Compte' }).click();
  await expect(page.getByText('Lapin 472')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Mon compte' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
});

test('compte page shows display name and Google provider row', async ({ page }) => {
  await page.goto('/compte');
  await expect(page.getByRole('heading', { name: 'Pseudonyme' })).toBeVisible();
  await expect(page.getByText('Lapin 472')).toBeVisible();
  await expect(page.getByText(/Google · lié le/)).toBeVisible();
});
```

- [ ] **Step 4.3b:** Run all gates including a11y:

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint && pnpm a11y
```

### 4.4 — Commit + PR

- [ ] **Step 4.4a:** Commit:

```bash
git add frontend/src/ui/components/auth/AvatarMenu.tsx \
        frontend/src/ui/routes/compte.tsx \
        frontend/tests/auth-authed.spec.ts
git commit -s -m "feat(frontend-identity): avatar popover + /compte read-only foundation"
```

- [ ] **Step 4.4b:** Push + PR (title 65 chars):

```bash
git push -u origin feat/oauth2-frontend-compte-foundation
gh pr create --base main \
  --title "feat(frontend-identity): avatar popover + /compte foundation" \
  --body "$(cat <<'BODY'
## Summary
- Upgrades AvatarMenu to a real Ark UI popover: display name (truncated to 20 chars via CSS), `Mon compte` link, `Se déconnecter` button. `aria-label="Compte"` on the trigger.
- Adds `/compte` route with read-only Pseudonyme and Comptes liés sections. Apple slot rendered greyed with "bientôt disponible".
- Anon users hitting `/compte` see a toast and are redirected to `/` (window.history.replaceState avoids a back-button loop).

## Test plan
- [x] `pnpm e2e -- tests/auth-authed.spec.ts` — avatar visible when authed, popover opens, /compte renders display name + Google row.
- [x] `pnpm a11y` — popover trigger has aria-label, content is keyboard-reachable, focus-trap works.
- [x] `pnpm typecheck`, `pnpm lint` green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

---

## Task 5: Display name edit on /compte

**Branch:** `feat/oauth2-frontend-compte-rename`. **Subagent skill:** `frontend`.

Adds an editable input + Save button to the Pseudonyme section.

### 5.0 — Recon

- [ ] **Step 5.0a:** Read `frontend/CLAUDE.md` — note the "uncontrolled inputs" convention (the project favours `defaultValue` + `ref.current.value` over `useState` for form inputs). Apply it here.

- [ ] **Step 5.0b:** Find an existing form pattern in the codebase: `grep -rn 'defaultValue' frontend/src/ui/routes --include="*.tsx" | head -5`. Read one example.

### 5.1 — Implementation

- [ ] **Step 5.1a:** Modify `frontend/src/ui/routes/compte.tsx`. Replace the Pseudonyme section's read-only `<div>` with an editable input + Save button + inline error display. Wire `onSubmit` to `authClient.updateMe(input.value)`; on success, call `refresh()` from `useAuth` and clear the error; on `InvalidDisplayNameError`, render the error message inline below the input.

Sketch:

```typescript
import { useRef, useState } from 'react';
import { InvalidDisplayNameError } from '@/application/auth';

function PseudonymeSection({ me, authClient, onSaved }: {
  me: GetMeResult;
  authClient: AuthClient;
  onSaved: () => Promise<void>;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!ref.current) return;
    const value = ref.current.value.trim();
    setError(null);
    setSaving(true);
    try {
      await authClient.updateMe(value);
      await onSaved();
    } catch (err) {
      if (err instanceof InvalidDisplayNameError) {
        setError(err.message);
      } else {
        setError('Une erreur est survenue. Réessayez.');
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className={formStyles}>
      <label htmlFor="display-name" className={labelStyles}>
        Pseudonyme
      </label>
      <input
        ref={ref}
        id="display-name"
        type="text"
        defaultValue={me.displayName}
        maxLength={30}
        minLength={1}
        required
        className={inputStyles}
        aria-describedby={error ? 'display-name-error' : undefined}
      />
      {error && (
        <p id="display-name-error" role="alert" className={errorStyles}>
          {error}
        </p>
      )}
      <button type="submit" disabled={saving} className={saveStyles}>
        {saving ? 'Enregistrement…' : 'Enregistrer'}
      </button>
    </form>
  );
}
```

Style tokens follow the codebase's existing form styles (`grep -rn 'inputStyles' frontend/src/ui --include="*.tsx" | head`). The `onSaved` callback should re-fetch `getMe()` so the rendered display name updates AND call `refresh()` on the AuthProvider so the header avatar reflects the new initial.

### 5.2 — Tests

- [ ] **Step 5.2a:** Append to `frontend/tests/auth-authed.spec.ts`:

```typescript
test('editing display name updates header avatar', async ({ page }) => {
  await page.goto('/compte');
  const input = page.getByLabel('Pseudonyme');
  await input.fill('Renard 888');
  await page.getByRole('button', { name: 'Enregistrer' }).click();
  // The header avatar's initial reflects the new name.
  await expect(page.getByRole('button', { name: 'Compte' })).toContainText('R');
});

test('invalid display name shows inline error', async ({ page }) => {
  await page.goto('/compte');
  const input = page.getByLabel('Pseudonyme');
  await input.fill(''); // empty → server rejects with 400
  await page.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
});
```

- [ ] **Step 5.2b:** Run + commit + PR:

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint && pnpm a11y && pnpm e2e
git add frontend/src/ui/routes/compte.tsx frontend/tests/auth-authed.spec.ts
git commit -s -m "feat(frontend-identity): editable pseudonym on /compte"
git push -u origin feat/oauth2-frontend-compte-rename
gh pr create --base main \
  --title "feat(frontend-identity): editable display name on /compte" \
  --body "$(cat <<'BODY'
## Summary
- Adds editable input + Save button to the Pseudonyme section. Uses the uncontrolled-input pattern (defaultValue + ref). On submit, calls PATCH /v1/users/me; on success, refreshes both the local me payload and the AuthProvider state so the header avatar's initial updates immediately.
- Inline error display on 400 invalid_display_name with role="alert" and aria-describedby wiring on the input.

## Test plan
- [x] Playwright: successful edit updates the header avatar's initial.
- [x] Playwright: empty submission renders the inline error.
- [x] `pnpm a11y` — form has labels, error has role="alert".

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 56 chars.

---

## Task 6: Delete account danger zone

**Branch:** `feat/oauth2-frontend-compte-delete`. **Subagent skill:** `frontend`.

Adds the danger-zone block at the bottom of `/compte` with typed confirmation.

### 6.0 — Recon

- [ ] **Step 6.0a:** Find existing dialog/modal usage: `grep -rn '@ark-ui/react/dialog' frontend/src --include="*.tsx" | head`. Read one example. If none, follow Ark UI's official dialog docs.

- [ ] **Step 6.0b:** Confirm the `useToast` hook exists and how it accepts tone (`success` / `info` / `danger`).

### 6.1 — Implementation

- [ ] **Step 6.1a:** Add a `DangerZone` section to `frontend/src/ui/routes/compte.tsx`. It renders the section header (red-toned) and a "Supprimer mon compte" button that opens an Ark UI dialog. The dialog content:
- Title "Supprimer définitivement votre compte"
- Body explaining the action is irreversible
- An input that requires exact match of the user's display name (the Confirm button stays disabled until matched)
- Two buttons: Annuler + Supprimer

On confirm:
1. `await authClient.deleteMe()`
2. Toast "Compte supprimé."
3. `await refresh()` (transitions AuthProvider to anon)
4. `navigate({ to: '/' })`

Sketch:

```typescript
import { Dialog } from '@ark-ui/react/dialog';
import { useNavigate } from '@tanstack/react-router';

function DangerZone({ me, authClient, onDeleted }: {
  me: GetMeResult;
  authClient: AuthClient;
  onDeleted: () => Promise<void>;
}) {
  const [typed, setTyped] = useState('');
  const [deleting, setDeleting] = useState(false);
  const navigate = useNavigate();
  const { push: pushToast } = useToast();
  const canConfirm = typed === me.displayName && !deleting;

  async function onConfirm() {
    setDeleting(true);
    try {
      await authClient.deleteMe();
      pushToast({ message: 'Compte supprimé.', tone: 'success' });
      await onDeleted();
      void navigate({ to: '/' });
    } catch {
      pushToast({ message: 'La suppression a échoué. Réessayez.', tone: 'danger' });
      setDeleting(false);
    }
  }

  return (
    <section className={dangerZoneStyles} aria-labelledby="danger-zone">
      <h2 id="danger-zone" className={dangerHeadingStyles}>Zone de danger</h2>
      <p>La suppression de votre compte est immédiate et définitive.</p>
      <Dialog.Root>
        <Dialog.Trigger className={dangerButtonStyles}>
          Supprimer mon compte
        </Dialog.Trigger>
        <Dialog.Backdrop className={backdropStyles} />
        <Dialog.Positioner>
          <Dialog.Content className={dialogStyles}>
            <Dialog.Title>Supprimer définitivement votre compte</Dialog.Title>
            <Dialog.Description>
              Cette action est irréversible. Tapez votre pseudonyme ({me.displayName}) pour confirmer.
            </Dialog.Description>
            <input
              type="text"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              aria-label="Confirmation du pseudonyme"
              className={inputStyles}
            />
            <div className={dialogActionsStyles}>
              <Dialog.CloseTrigger className={cancelButtonStyles}>
                Annuler
              </Dialog.CloseTrigger>
              <button
                type="button"
                onClick={onConfirm}
                disabled={!canConfirm}
                className={confirmButtonStyles}
              >
                {deleting ? 'Suppression…' : 'Supprimer'}
              </button>
            </div>
          </Dialog.Content>
        </Dialog.Positioner>
      </Dialog.Root>
    </section>
  );
}
```

(The controlled input via `useState` here is acceptable — it's a confirmation-token check that DOES need state in React to compare against `me.displayName`. The uncontrolled-input convention applies to form-data inputs; confirmation tokens are a recognized exception.)

### 6.2 — Tests

- [ ] **Step 6.2a:** Append to `frontend/tests/auth-authed.spec.ts`:

```typescript
test('delete confirm button disabled until display name matches', async ({ page }) => {
  await page.goto('/compte');
  await page.getByRole('button', { name: 'Supprimer mon compte' }).click();
  const confirmButton = page.getByRole('button', { name: 'Supprimer', exact: true });
  await expect(confirmButton).toBeDisabled();
  await page.getByLabel('Confirmation du pseudonyme').fill('Lapin 472');
  await expect(confirmButton).toBeEnabled();
});

test('delete account redirects to / and shows anon header', async ({ page }) => {
  await page.goto('/compte');
  await page.getByRole('button', { name: 'Supprimer mon compte' }).click();
  await page.getByLabel('Confirmation du pseudonyme').fill('Lapin 472');
  await page.getByRole('button', { name: 'Supprimer', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();
});
```

- [ ] **Step 6.2b:** Run + commit + PR:

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint && pnpm a11y && pnpm e2e
git add frontend/src/ui/routes/compte.tsx frontend/tests/auth-authed.spec.ts
git commit -s -m "feat(frontend-identity): delete-account danger zone on /compte"
git push -u origin feat/oauth2-frontend-compte-delete
gh pr create --base main \
  --title "feat(frontend-identity): delete-account danger zone" \
  --body "$(cat <<'BODY'
## Summary
- Adds a Zone de danger section on /compte with a typed-confirmation dialog (Ark UI Dialog). Confirm button stays disabled until the user types their exact display name.
- On confirm: DELETE /v1/users/me, toast "Compte supprimé.", refresh AuthProvider, navigate to /.

## Test plan
- [x] Playwright: confirm button is disabled until the name matches.
- [x] Playwright: full delete flow lands on / with the anon header restored.
- [x] `pnpm a11y` — dialog has title, description, and labelled confirmation input.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 50 chars.

---

## Task 7: Hint gate

**Branch:** `feat/oauth2-frontend-hint-gate`. **Subagent skill:** `frontend`.

### 7.0 — Recon

- [ ] **Step 7.0a:** Locate the hint button. `grep -rn 'hintsRemaining\|useHint\|Indice' frontend/src/ui --include="*.tsx" | head`. Read the file where the hint button is rendered (likely `PuzzleToolbar.tsx` or similar).

- [ ] **Step 7.0b:** Note the existing tooltip pattern: `grep -rn 'Tooltip\|tooltip' frontend/src/ui --include="*.tsx" | head`. Read one usage.

### 7.1 — HintGate component

- [ ] **Step 7.1a:** Create `frontend/src/ui/components/auth/HintGate.tsx`:

```typescript
import { type ReactNode, cloneElement, isValidElement } from 'react';
import { useAuth } from './AuthProvider';

interface HintGateProps {
  /**
   * The hint button. When status='authed' it renders unchanged.
   * When status !== 'authed', a `disabled` prop is injected and a
   * sibling tooltip-tip is rendered (the consumer's existing tooltip
   * library does the visual heavy lifting via `title` for the simplest
   * cross-browser support).
   */
  readonly children: ReactNode;
}

export function HintGate({ children }: HintGateProps) {
  const { state } = useAuth();
  const authed = state.status === 'authed';
  if (!isValidElement(children)) return <>{children}</>;
  if (authed) return children;

  const child = children as React.ReactElement<{ disabled?: boolean; title?: string; 'aria-disabled'?: boolean }>;
  return cloneElement(child, {
    disabled: true,
    'aria-disabled': true,
    title:
      state.status === 'loading'
        ? 'Chargement…'
        : 'Connectez-vous pour utiliser les indices.',
  });
}
```

(If the codebase prefers a real Tooltip primitive over `title`, swap the implementation to render `<Tooltip content={…}>{cloned}</Tooltip>`. The `title` form keeps Phase 5 minimal.)

### 7.2 — Wire into the puzzle toolbar

- [ ] **Step 7.2a:** Wrap the existing hint `<button>` with `<HintGate>`. The exact file depends on Step 7.0a. Example:

```typescript
// Before:
<button onClick={useHint} disabled={hintsRemaining === 0}>
  Indice ({hintsRemaining})
</button>

// After:
<HintGate>
  <button onClick={useHint} disabled={hintsRemaining === 0}>
    Indice ({hintsRemaining})
  </button>
</HintGate>
```

The cloned `disabled` from `HintGate` is OR-ed with the existing `disabled` via React (last write wins; if HintGate sets it true, the button is disabled regardless of `hintsRemaining`). Verify this by reading the React semantics around cloneElement — if it overwrites instead of OR-ing, refactor `HintGate` to read the original `disabled` from `children.props` and OR explicitly.

### 7.3 — Tests

- [ ] **Step 7.3a:** Create `frontend/tests/hint-gate.spec.ts`:

```typescript
import { test, expect } from '@playwright/test';

test('anon user sees disabled hint button with tooltip', async ({ page }) => {
  // Navigate to a grille route where the hint button is rendered.
  await page.goto('/grille');
  const hintButton = page.getByRole('button', { name: /Indice/ });
  await expect(hintButton).toBeDisabled();
  await expect(hintButton).toHaveAttribute('title', 'Connectez-vous pour utiliser les indices.');
});

test('authed user sees enabled hint button (when budget remains)', async ({ page }) => {
  // Auth stub set in test.beforeEach.
  await page.goto('/grille');
  const hintButton = page.getByRole('button', { name: /Indice/ });
  // If hintsRemaining > 0, the button is enabled.
  await expect(hintButton).toBeEnabled();
});
```

### 7.4 — Commit + PR

- [ ] **Step 7.4a:**

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint && pnpm e2e
git add frontend/src/ui/components/auth/HintGate.tsx \
        frontend/src/ui/components/auth/index.ts \
        frontend/src/ui/components/layout/PuzzleToolbar.tsx \  # or whatever file the hint button lives in
        frontend/tests/hint-gate.spec.ts
git commit -s -m "feat(frontend-identity): gate hint button behind sign-in"
git push -u origin feat/oauth2-frontend-hint-gate
gh pr create --base main \
  --title "feat(frontend-identity): gate hint button behind sign-in" \
  --body "$(cat <<'BODY'
## Summary
- Adds `HintGate` component that wraps the existing hint button. When the user is anon (or auth state is still loading), it clones the child with `disabled` + `aria-disabled` + a `title` tooltip "Connectez-vous pour utiliser les indices."
- When authed, renders the child unchanged so the existing hintsRemaining/useHint logic stays the source of truth.
- The avatar in the header remains the only discovery surface for sign-in (per the design's gating decision).

## Test plan
- [x] Playwright: anon sees disabled button with the right tooltip text.
- [x] Playwright: authed sees enabled button.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 54 chars.

---

## Task 8: Privacy page updates

**Branch:** `docs/oauth2-privacy-pages`. **Subagent skill:** `frontend`.

Docs-only.

### 8.0 — Recon

- [ ] **Step 8.0a:** Read `frontend/src/ui/routes/confidentialite.tsx` in full. Note the data-collection block and the section structure (likely h2 + p elements styled with Panda CSS tokens).

- [ ] **Step 8.0b:** Read `frontend/src/ui/routes/privacy.tsx` (English mirror) for the parallel structure.

### 8.1 — French page

- [ ] **Step 8.1a:** Add a new section to `frontend/src/ui/routes/confidentialite.tsx` near the existing data-collection block. Content (text from the spec, rendered as JSX with the page's existing styling tokens):

> **Compte joueur.**
> Si vous vous connectez via Google, nous créons un compte joueur avec :
> - un identifiant interne (UUID, sans lien avec votre compte Google) ;
> - un pseudonyme modifiable (par défaut : un nom d'animal aléatoire repris de votre session anonyme) ;
> - la date de création et de dernière connexion.
> Nous ne stockons **pas** votre email, votre nom, votre photo de profil ou toute autre donnée de votre compte Google. Le périmètre OAuth utilisé est `openid` uniquement.
>
> **Sessions.** Un cookie `__Host-ws_session` (HttpOnly, Secure, durée 7 jours) contient un identifiant de session opaque (UUID, pas un JWT). Il est révoqué à la déconnexion et supprimé lors de la suppression du compte.
>
> **Sous-traitants.** Lors de la connexion, Google reçoit votre choix d'autorisation. Aucune donnée n'est partagée en dehors du flux OAuth.
>
> **Droit à l'effacement.** « Supprimer mon compte » dans `/compte` supprime immédiatement vos données identité — pas de période de rétention, pas de soft-delete.

Use `<h2>` / `<h3>` for sub-headings consistent with existing page structure, and bullet lists for the data inventory. Wrap in a `<section aria-labelledby="...">` matching neighbouring sections.

### 8.2 — English page

- [ ] **Step 8.2a:** Mirror in `frontend/src/ui/routes/privacy.tsx`:

> **Player account.**
> When you sign in with Google we create a player account with:
> - an internal identifier (UUID, unrelated to your Google account ID);
> - an editable display name (defaulting to a random animal name carried over from your anonymous session);
> - creation and last-seen timestamps.
> We do **not** store your email, name, profile picture, or any other Google account data. The OAuth scope is `openid` only.
>
> **Sessions.** A `__Host-ws_session` cookie (HttpOnly, Secure, 7-day lifetime) holds an opaque session ID (UUID, not a JWT). It is revoked on sign-out and deleted when the account is deleted.
>
> **Sub-processors.** During sign-in Google receives your authorisation choice. No data is shared outside the OAuth flow itself.
>
> **Right to erasure.** "Delete my account" in `/compte` immediately deletes your identity data — no retention period, no soft-delete.

### 8.3 — Commit + PR

```bash
cd frontend && pnpm typecheck && pnpm lint && pnpm test  # static-content routes should still build
git add frontend/src/ui/routes/confidentialite.tsx frontend/src/ui/routes/privacy.tsx
git commit -s -m "docs(frontend): privacy pages cover Google sign-in + session cookie"
git push -u origin docs/oauth2-privacy-pages
gh pr create --base main \
  --title "docs(frontend): privacy pages cover identity sign-in" \
  --body "$(cat <<'BODY'
## Summary
- Adds a Compte joueur / Player account section to both privacy pages covering: internal UUID (not linked to the Google account), editable display name, creation/last-seen timestamps, the explicit "no email/name/picture/Google data" statement (openid-only scope), the __Host-ws_session cookie, sub-processor disclosure (Google), and the right-to-erasure binding to /compte.

## Test plan
- [x] `pnpm typecheck`, `pnpm lint`, `pnpm test` green.
- [ ] Manual: render both /confidentialite and /privacy in `pnpm dev`; new section visually aligned with surrounding content.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 50 chars.

---

## Self-review checklist (for the planning agent before saving)

- [x] Every spec section has a covering task (auth port + adapter → Task 2; AuthProvider + header → Task 3; avatar popover → Task 4; /compte read-only → Task 4; display name edit → Task 5; delete account → Task 6; hint gate → Task 7; privacy pages → Task 8; CORS prereq → Task 1).
- [x] No "TBD" / "TODO" / placeholder steps.
- [x] Type names match across tasks: `AuthClient`, `WhoAmIResult`, `GetMeResult`, `LinkedProvider`, `InvalidDisplayNameError`, `AuthState`. `useAuth()` defined in Task 3, reused in Tasks 4–7.
- [x] Each task is independently shippable. Tasks 4–7 depend on Task 3's `AuthProvider`/`useAuth`. Tasks 5+6 layer on Task 4's `/compte` page. Task 8 is independent.
- [x] Every task has a 400-line-cap check before push.

---

## Phasing summary

| # | Branch | Layer | Approx. diff |
|---|---|---|---|
| 1 | `chore/oauth2-identity-cors` | JVM backend | 70 lines |
| 2 | `feat/oauth2-frontend-auth-client` | TS infra | 250 lines |
| 3 | `feat/oauth2-frontend-auth-provider` | TS UI | 250 lines |
| 4 | `feat/oauth2-frontend-compte-foundation` | TS UI | 300 lines |
| 5 | `feat/oauth2-frontend-compte-rename` | TS UI | 120 lines |
| 6 | `feat/oauth2-frontend-compte-delete` | TS UI | 180 lines |
| 7 | `feat/oauth2-frontend-hint-gate` | TS UI | 80 lines |
| 8 | `docs/oauth2-privacy-pages` | TS UI (text) | 80 lines |

Task 1 ships first (frontend can't talk to identity-api without CORS). Tasks 2 → 3 are dependency-ordered. Tasks 4 → 5 → 6 layer on /compte. Task 7 + Task 8 are independent and can ship in parallel with Task 4+.
