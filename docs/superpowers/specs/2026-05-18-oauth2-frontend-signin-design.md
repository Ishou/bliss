# OAuth2 Player Sign-In — Phase 5: Frontend surface

**Status:** Approved 2026-05-18.

**Context.** The identity-api shipped in Phase 4 + 4.5 is live at
`https://auth.wordsparrow.io/`. Google OIDC sign-in works end-to-end on
the backend (whoami / me / logout / delete / link routes all green;
`__Host-ws_session` cookie issued by the callback; CNPG-backed Postgres
storing users + sessions). Anonymous play continues to work via the
existing localStorage `SessionClient`. Phase 5 adds the frontend
surface so users can actually sign in and the gated feature (hints)
switches off for anon.

Phase 4 spec: `docs/superpowers/specs/2026-05-16-oauth2-player-signin-design.md`.

## Scope

**In Phase 5:**
- Header avatar + "Se connecter" button (right slot of `AppHeader`).
- Direct redirect to Google sign-in on button click (Google-only for now;
  Apple deferred until Apple Developer Program membership is paid).
- React auth context (`whoami` on mount, re-check on visibility change).
- Account route `/compte` — display name edit, linked providers list,
  delete-account danger zone with typed confirmation.
- Avatar popover — display name + "Mon compte" link + "Se déconnecter".
- Hint button gating — disabled + tooltip when anon.
- Privacy page updates — new section covering identity data, the
  session cookie, third-party processors, and right-to-erasure.
- Backend prerequisite PR: Ktor CORS plugin on identity-api permitting
  the frontend origins with credentials.

**Out of scope (Phase 6):**
- Anon ↔ authed state fusion during in-game sign-in. Today, signing in
  on `/grille/X` returns the player to the same URL with the
  localStorage anon state still in memory and the new auth state
  separately. Fusing means server-side migration of anon `session_id`
  data (hint history, solo entries, lobby membership) onto the new
  `user_id`, so the player resumes the same puzzle/lobby state from
  the authed side. Touches grid-api (per-user hint history),
  game-api (lobby player identity), frontend (replace localStorage
  reads with server reads when authed). First-class Phase 6
  workstream.
- Server-side hint history per authed user (grid-api change).
- Server-side streak / cooldown tracking (not yet implemented anywhere).

## Decisions

| Decision | Choice |
|---|---|
| Sign-in entry | Header right slot. Anon: "Se connecter" button. Authed: avatar popover. |
| Sign-in action | Direct redirect to `auth.wordsparrow.io/v1/auth/google/login?return_to=<current>`. Full-page navigation, not fetch. |
| Account UI | Avatar popover for quick actions; dedicated `/compte` route for the heavy ones. |
| Hint gating | Disabled `<button disabled>` + tooltip `"Connectez-vous pour utiliser les indices."`. Avatar header is the only sign-in discovery surface. |
| Delete account | Typed confirmation (user types their display name) in `/compte` danger zone. |
| Sign-out | One click, no confirm. |
| Default display name | Server-side default is `"Joueur"` (already implemented). Frontend, on first sign-in, detects this and PATCHes with the localStorage anon pseudonym (animal name) so identity is continuous. |
| Anon localStorage on sign-in | Untouched. No merge, no wipe. Phase 6 defines the migration. |

## Architecture

New module under `frontend/src/`:

```
application/auth/
  AuthClient.ts          # hexagonal port
infrastructure/auth/
  HttpAuthClient.ts      # fetch adapter against auth.wordsparrow.io
ui/components/auth/
  AuthProvider.tsx       # React context + useAuth() hook
  HeaderAuthSlot.tsx     # routes between SignInButton + AvatarMenu + skeleton
  SignInButton.tsx       # anon-state header button (anchor → identity-api)
  AvatarMenu.tsx         # Ark UI popover anchored to the avatar
  HintGate.tsx           # wraps hint buttons; disables + tooltips when anon
ui/routes/
  compte.tsx             # /compte page
```

`ui/` only depends on the application port `AuthClient`. The
`HttpAuthClient` is wired in `main.tsx` and threaded through
`AppRouterContext` next to `sessionClient`, `puzzleRepository`, etc.
The `boundaries:element-types` ESLint rule (ADR-0002 §7) keeps the
hexagonal cut clean.

`AuthClient` surface:

```ts
interface WhoAmIResult { userId: string; displayName: string; }
interface GetMeResult {
  id: string;
  displayName: string;
  createdAt: string;
  lastSeenAt: string;
  linkedProviders: ReadonlyArray<{
    provider: 'google' | 'apple';
    linkedAt: string;
    emailOptIn: boolean;
  }>;
}

interface AuthClient {
  whoami(): Promise<WhoAmIResult | null>;       // null on 401
  getMe(): Promise<GetMeResult>;                // throws on 401
  updateMe(displayName: string): Promise<void>; // throws InvalidDisplayName on 400
  deleteMe(): Promise<void>;                    // 204
  logout(): Promise<void>;                      // 204
  signInUrl(returnTo: string): string;          // builder for the <a href=>
}
```

`AuthProvider` keeps three states: `loading | { status: 'anon' } |
{ status: 'authed'; whoami: WhoAmIResult }`. On mount it calls
`whoami()`. On `visibilitychange → visible` it re-calls so a sign-in
in another tab updates this tab without manual reload.

## User flows

### Sign-in (anon → authed)

1. User clicks "Se connecter" — a real `<a href="https://auth.wordsparrow.io/v1/auth/google/login?return_to=<currentURL>">`. Full-page navigation (the 302 chain and Set-Cookie need browser semantics; fetch doesn't follow cross-origin 302s and CORS would block them anyway).
2. Google consent → callback to identity-api → `Set-Cookie: __Host-ws_session=…; HttpOnly; Secure; SameSite=Lax; Max-Age=604800` → 302 to `return_to`.
3. Browser lands back on the page the user came from. AppShell mounts. `AuthProvider` calls `whoami()` → 200. Avatar replaces the "Se connecter" button.
4. **First-sign-in detection.** `AuthProvider` is the owner. After a successful `whoami()`, if `whoami.displayName === 'Joueur'` (the server default) AND `localStorageSession.getPseudonym()` returns an auto-generated default animal name (i.e. `isDefaultPseudonym(local)` returns `true`), it calls `updateMe(localStoragePseudonym)` once and refreshes its own state from the response. Idempotent and self-disabling on subsequent boots (after the patch, `displayName !== 'Joueur'`).

### Avatar popover

Ark UI `Popover.Root` anchored to the avatar button. Trigger has `aria-label="Compte"`. Content:

```
┌──────────────────────────┐
│ Lapin 472                │  ← truncated to 20 chars
│                          │
│ → Mon compte             │  ← <Link to="/compte">
│                          │
│ ⏻  Se déconnecter         │  ← <button> calls logout()
└──────────────────────────┘
```

Sign-out: `POST /v1/auth/logout` with `credentials: 'include'`. On 204, `AuthProvider` transitions to `anon`; navigate to `/`.

### `/compte` route

Three sections, vertically stacked, max-width matching the puzzle page.

1. **Display name.** Single-line input pre-filled from `getMe().displayName`. Save button → `PATCH /v1/users/me { displayName }` (only the `displayName` field; `emailOptIn` is part of the OpenAPI `UserUpdate` schema but the frontend does not surface it in Phase 5 — the opt-in consent screen is out of Phase 5 scope (deferred workstream)). Inline error message on 400 `invalid_display_name` ("Le pseudo doit faire entre 1 et 30 caractères."). Optimistic update on 200; refresh `AuthProvider`'s `whoami` state on success.

2. **Comptes liés.** Read-only list:
   - "Google · lié le 17 mai 2026" with a check-circle icon. If `emailOptIn` is true (data we stored at link time when the user consented to email), show a small "Reçoit les emails" badge.
   - Apple slot present but greyed: "Apple · bientôt disponible". No "Lier un compte Apple" CTA yet; the backend route exists but Apple Developer Program is unpaid.

3. **Danger zone.** Sage→red token flip for the section header. "Supprimer mon compte" button opens a modal dialog:
   - Body explains the action is irreversible.
   - Input requires exact match of the current display name. Confirm button stays disabled until the input matches.
   - On confirm: `DELETE /v1/users/me` → 204 → toast `"Compte supprimé."` → `navigate('/')` → `AuthProvider` transitions to `anon`.

### Hint gate

`useAuth()` in the puzzle toolbar:

```tsx
const { status } = useAuth();
const disabled = status !== 'authed';
return (
  <Tooltip content={disabled ? 'Connectez-vous pour utiliser les indices.' : null}>
    <button disabled={disabled} aria-label={…}>
      Indice ({hintsRemaining})
    </button>
  </Tooltip>
);
```

A native `<button disabled>` means click events do not fire — no redirect, no dialog. The avatar in the header is the only discovery surface for sign-in. Loading state shows the button disabled with a generic "Chargement…" tooltip.

## Backend prerequisite

Identity-api needs CORS so the frontend at `wordsparrow.io` can call `auth.wordsparrow.io` cross-origin with credentials. New PR (small, lands first):

```kotlin
// identity/api/.../Module.kt
install(CORS) {
    allowHost("wordsparrow.io", schemes = listOf("https"))
    allowHost("www.wordsparrow.io", schemes = listOf("https"))
    allowHost("bliss-cb4.pages.dev", schemes = listOf("https"))
    allowHost("localhost:5173", schemes = listOf("http"))
    allowMethod(HttpMethod.Patch)
    allowMethod(HttpMethod.Delete)
    allowHeader(HttpHeaders.ContentType)
    allowCredentials = true
    maxAgeInSeconds = 600
}
```

(GET + POST + OPTIONS are allowed by Ktor's CORS default; explicit
PATCH/DELETE plus Content-Type are the only additions.) Test:
`OPTIONS` preflight from `https://wordsparrow.io` returns
`Access-Control-Allow-Origin: https://wordsparrow.io` +
`Access-Control-Allow-Credentials: true`. ApiArchitectureTest does not
need to change — CORS is in `io.ktor.server.plugins.cors.*`, no vendor
SDK.

## Privacy page updates

Both `ui/routes/confidentialite.tsx` (French) and `ui/routes/privacy.tsx` (English) get a new section near the existing data-collection block.

Content (French; English mirrors):

> **Compte joueur.**
> Si vous vous connectez via Google, nous créons un compte joueur avec :
> - un identifiant interne (UUID, sans lien avec votre compte Google) ;
> - un pseudonyme modifiable (par défaut : un nom d'animal aléatoire repris de votre session anonyme) ;
> - la date de création et de dernière connexion.
> Nous ne stockons **pas** votre email, votre nom, votre photo de profil ou toute autre donnée de votre compte Google. Le périmètre OAuth utilisé est `openid` uniquement (ADR-0045).
>
> **Sessions.** Un cookie `__Host-ws_session` (HttpOnly, Secure, durée 7 jours) contient un identifiant de session opaque (UUID, pas un JWT). Il est révoqué à la déconnexion et supprimé lors de la suppression du compte.
>
> **Sous-traitants.** Lors de la connexion, Google reçoit votre choix d'autorisation. Aucune donnée n'est partagée en dehors du flux OAuth.
>
> **Droit à l'effacement.** « Supprimer mon compte » dans `/compte` supprime immédiatement vos données identité — pas de période de rétention, pas de soft-delete.

Total addition per page: ~30 lines including markup.

## Testing

- **Vitest** (frontend unit):
  - `HttpAuthClient` mocked via MSW — happy path + 401 → null + 400 InvalidDisplayName.
  - `AuthProvider` state transitions on mock whoami responses.
  - `HintGate` renders disabled when status='anon' and enabled when 'authed'.
- **Playwright** (e2e, in `frontend/tests/`):
  - Unauthenticated user sees "Se connecter" button.
  - Hint button is disabled + tooltip visible on hover.
  - `/compte` when anon: the route component calls `const { status } = useAuth()` and in a `useEffect` navigates to `/` with a toast `"Connectez-vous pour accéder à votre compte."` when `status === 'anon'`. No 404 — the route exists for everyone, the gate is component-level and state-driven (consistent with how other routes in this codebase are guarded; no `beforeLoad` is used).
  - Authenticated path stubs the OAuth round-trip (MSW + a fixture cookie) and verifies the avatar popover + display-name edit + delete-account modal flow.
- **a11y (axe-core via Playwright):** the avatar popover, the typed-confirm dialog, and the `/compte` form all pass WCAG AA per ADR-0034.
- **Spec compliance:** the design's "Decisions" table is the contract — every row must be covered by either a Vitest or Playwright assertion.

## Risks / open questions

- **First-sign-in PATCH race.** If a user signs in on two tabs at once, both may fire the "name is 'Joueur', patch with anon pseudonym" PATCH. PATCHes are idempotent (last writer wins, same value usually) — acceptable.
- **Anon-pseudonym mismatch.** If the user previously edited their anon pseudonym to something like `"Joueur"` literally, the first-sign-in heuristic would skip patching. They keep the server default. Acceptable.
- **CORS misconfig.** A typo in `allowHost(…)` will break sign-in silently (network panel shows CORS error). The backend PR ships with an integration test that asserts the preflight headers; the frontend's MSW handlers don't exercise CORS so this is a real "test against the live backend" concern at deploy time.
- **Apple slot greyed out.** Users may be confused by the disabled Apple row in `/compte`. Acceptable for alpha; deferring removes the row entirely once Apple is paid.

## Implementation phasing

Sub-PRs (each one a small focused diff):

1. **Backend CORS** — Ktor CORS plugin in identity-api Module + integration test. Lands first.
2. **AuthClient port + HTTP adapter** — `application/auth/AuthClient.ts` + `infrastructure/auth/HttpAuthClient.ts` + MSW handlers + unit tests. No UI yet.
3. **AuthProvider + HeaderAuthSlot + SignInButton** — wires auth state into the app shell. The header now shows "Se connecter" for anon, an avatar for authed (avatar opens a stub popover with just sign-out). Includes the first-sign-in pseudonym carry-over.
4. **AvatarMenu + /compte route foundation** — full popover with the Mon-compte link. `/compte` shows display name (read-only) + linked providers. No edit, no delete.
5. **Display name edit on /compte** — input + Save + inline error + Auth refresh.
6. **Delete account danger zone** — typed confirm modal + DELETE flow + toast + redirect.
7. **Hint gate** — `HintGate.tsx` wired into the puzzle toolbar. Disabled + tooltip when anon.
8. **Privacy page updates** — French + English additions. Docs-only.

Each ships behind no flag (alpha, sandbox). The order keeps every PR independently shippable — if (3) merges and (4) doesn't ship for a week, anon users see the right header and authed users still get a working avatar popover (degraded to sign-out only).
