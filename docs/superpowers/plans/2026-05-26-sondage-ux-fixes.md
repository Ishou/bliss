# Sondage UX fixes (2026-05-26)

> **For agentic workers:** REQUIRED SUB-SKILL: invoke `dispatch` at session start. This plan is the canonical wave map for the post-deploy sondage UX bugs.

**Goal.** Resolve the post-deploy UX regressions on /sondage and /compte surfaced by the maintainer after the 2026-05-26 survey-module rollout. Five independent PRs, one wave.

**Architecture.** No new abstractions. Frontend-only for A–D; E starts as a Playwright repro that may surface a small backend change.

**Tech stack.** Existing.

---

## Wave A — five PRs in parallel

All five touch disjoint files; no schema-first dependencies. Dispatch in a single assistant turn with `isolation: "worktree"` and `run_in_background: true`.

| PR  | Title                                                                       | Context · Layer          | Approx LOC |
|-----|-----------------------------------------------------------------------------|--------------------------|------------|
| A   | Add /sondage to AppHeader main nav                                          | `frontend` · `ui`        | ~5         |
| B   | Force-prerender /sondage + /compte (bypass `noindex` skip)                  | `frontend` · build       | ~30        |
| C   | French labels for pos/categorie/style + drop UPPERCASE chips + difficulté   | `frontend` · `ui`        | ~120       |
| D   | FlagPicker uses Ark UI Select instead of native `<select>`                  | `frontend` · `ui`        | ~80        |
| E   | Reproduce + remediate the /compte PATCH 401                                 | `survey` · multi-layer   | ~60 (TBD)  |

---

## PR A — `/sondage` in main nav

**Files**
- Modify: `frontend/src/ui/components/layout/AppHeader.tsx` — add the entry to `NAV_LINKS`.
- Modify (if applicable): the matching test (`frontend/tests/app-header*.test.tsx` if it exists).

**Mandatory ADR pre-read (run `scripts/adr-context.sh`)**
- ADR-0050 — a11y baseline (nav must keep keyboard + screen-reader semantics)
- ADR-0054 — page shell primitive (where the nav lives)

**Spec**
- Add `{ id: 'sondage', label: 'Sondage', href: '/sondage' }` to the `NAV_LINKS` const, between `grilles` and `aide`.
- TanStack Router's `<Link to=>` type-union must accept `/sondage` — it should already since the route is registered.
- The mobile / hamburger nav (if any) inherits from the same source — no separate change needed.

**Success criteria**
- `pnpm typecheck` passes (the route-union should already include `/sondage`).
- `pnpm test`, `pnpm lint`, `pnpm a11y` pass.
- Manual smoke: `/sondage` link appears in the header, navigating from `/` doesn't cause a flash (B will address the prerender side).

**Risks**
- The `NAV_LINKS` type may need a new union member if it's narrowly typed. Add it.
- Mobile nav assertion test (if present) needs the new entry counted.

---

## PR B — prerender /sondage + /compte

**Files**
- Modify: prerender configuration / route list (check `frontend/scripts/prerender*` and any `INDEXABLE_ROUTES`/`PRERENDER_ROUTES` constant — they're the source of truth for the Cloudflare static-HTML output).
- Modify: `frontend/src/ui/routes/sondage.tsx` and `frontend/src/ui/routes/compte.tsx` — keep `noindex: true` for SEO but ensure the route is in the prerender list.

**Mandatory ADR pre-read**
- ADR-0053 — build-time prerender for SEO
- ADR-0050 — a11y baseline

**Symptom (repro before fix)**
- `curl -s https://wordsparrow.io/sondage | grep '<title>'` → `<title>WordSparrow — mots fléchés français en ligne</title>` (HOME page title, not /sondage's).
- Same shape for `/compte`.
- Compare: `/grilles` returns its own title at first byte — that route IS prerendered.

**Spec**
- Find the prerender pipeline (`frontend/scripts/prerender-static.ts` or similar) and the route list it iterates.
- Verify whether it filters by `head().noindex` and skips noindex routes — that's the most likely cause. If so, decouple "render to static" from "indexable for search engines" — both /sondage and /compte should ship static HTML even though their `<head>` includes `<meta name="robots" content="noindex">`.
- The static HTML's `<noscript>` should already render a "Chargement…" placeholder that the page-shell skeleton matches; if not, add it so users with JS disabled see something sensible.

**Success criteria**
- `pnpm build` runs prerender; `dist/sondage/index.html` and `dist/compte/index.html` exist.
- Each prerendered file has its own `<title>` (Sondage — WordSparrow / Mon compte — WordSparrow).
- Each prerendered file still has `<meta name="robots" content="noindex">`.
- Post-merge + deploy: `curl -s https://wordsparrow.io/sondage | grep '<title>'` returns "Sondage — WordSparrow" — proves the static HTML is correct.

**Risks**
- The prerender script may pre-fetch route data; for `/sondage` that means a survey-api call from the build environment, which may not be reachable. Use mock route context or skip data prefetch — render the shell only.
- `/compte` route has an auth guard that redirects to `/` for unauthed users. Build-time render should treat the user as anon (no cookie); the rendered HTML should show the loading state. That's fine; the unauthed redirect runs client-side after hydration.

---

## PR C — French labels for pos/categorie/style + drop UPPERCASE chips + difficulté wording

**Files**
- Create: `frontend/src/ui/components/sondage/labels.ts` — pure label maps for `pos`, `categorie`, `style`, plus a `difficulteLabel(n)` helper if useful.
- Modify: `frontend/src/ui/components/sondage/RatingCard.tsx`:
  - Render `posLabel(item.pos)` / `categorieLabel(item.categorie)` / `styleLabel(item.style)` instead of raw enum values.
  - Drop `textTransform: 'uppercase'` from `chipStyles` (or replace with a less aggressive style).
  - Rename "force annoncée" → "difficulté annoncée" so the chip and the Likert use the same word.

**Mandatory ADR pre-read**
- ADR-0050 — a11y (labels carry semantic meaning, not just aesthetic)
- ADR-0056 — survey bounded context (the enum values are domain truth from `survey/domain/...`)

**Spec — label maps**

Read `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/` to get the canonical enum set. The wire transport (per OpenAPI) lowercases them via Jackson convention → e.g. `nom_commun`. Map each to a human French phrase:

- `pos`: `nom_commun → "Nom commun"`, `nom_propre → "Nom propre"`, `verbe_infinitif → "Verbe (infinitif)"`, `verbe_conjugue → "Verbe (conjugué)"`, `adjectif → "Adjectif"`, `adverbe → "Adverbe"`, ... cover the full set from the openapi schema.
- `categorie`: `aliments → "Aliments"`, `transports → "Transports"`, `mobilier_objet → "Mobilier / objet"`, `sentiments_etats → "Sentiments / états"`, `professions → "Professions"`, `arts → "Arts"`, `vetements → "Vêtements"`, `body_parts → "Parties du corps"`, `lieux → "Lieux"`, ... full set.
- `style`: `definition_directe → "Définition directe"`, `periphrase → "Périphrase"`, `culturel → "Culturel"`, `cryptique → "Cryptique"`, `fonction_role → "Fonction / rôle"`, ... full set.

**Use the OpenAPI schema (`survey/api/openapi.yaml`) as the canonical source for the enum sets — don't rely on what's currently in the seed CSV; the schema is authoritative.**

**Spec — chip styles**
- Drop `textTransform: 'uppercase'`. Keep the chip's other attributes (border, color).
- Optional: keep `letterSpacing: '0.04em'` for visual continuity, or remove it too.

**Spec — wording**
- Line 136 of RatingCard.tsx: change `"style : {styleLabel(item.style)} · force annoncée : {item.forceClaimed}"` to `"Style : {styleLabel(item.style)} · Difficulté annoncée : {item.forceClaimed}"`.
- Keep the Likert's `Difficulté réelle :` label as-is — now matches.

**Success criteria**
- `pnpm test`: existing RatingCard tests still pass; add a snapshot or focused test asserting "Nom commun" appears (not "nom_commun" or "NOM_COMMUN").
- `pnpm a11y`: clean.
- Manual: load /sondage on prod after deploy, every chip should be a human French phrase.

**Risks**
- The enum set may include values not yet seen in the seed CSV but present in the schema. Cover all schema values; mark any TBD with a TODO comment ONLY if necessary (prefer to translate them all up front based on the schema).
- If a wire value arrives that's not in the label map (e.g. server adds a new enum without frontend update), fall back gracefully to the raw value rather than throwing.

---

## PR D — FlagPicker uses Ark UI Select

**Files**
- Modify: `frontend/src/ui/components/sondage/FlagPicker.tsx` — replace the native `<select>` with Ark UI's `Select.Root` (+ `Select.Trigger`, `Select.Content`, `Select.Item`, etc).
- Possibly: `frontend/src/ui/components/primitives/Select.tsx` — if no project-wide Select primitive exists yet, create one wrapping Ark UI's Select with the project's styling. Otherwise reuse.

**Mandatory ADR pre-read**
- ADR-0002 — frontend stack (Ark UI conventions)
- ADR-0050 — a11y baseline (Ark UI must preserve keyboard + screen-reader semantics that native `<select>` provided)

**Spec**
- Look at how `Likert`, `Button`, `Dialog`, `TextField`, `useToast` are organised under `frontend/src/ui/components/primitives/` — Ark UI usage pattern.
- Replace the native `<select>` with Ark UI's `Select.Root` from `@ark-ui/react`. Keep the same options + values + `value`/`onChange` contract.
- Preserve the existing label (`"Signaler un problème (optionnel)"`), keyboard navigation, and the "— Aucun —" empty-state option.
- Style with Panda's `css({...})` to match the visual weight of the other form controls (Likert, TextField).

**Success criteria**
- `pnpm test`: existing FlagPicker tests pass (if absent, add at minimum: open, select an option, assert onChange fires with the right value; clear back to undefined via the "— Aucun —" option).
- `pnpm a11y`: clean.
- Manual: visually consistent with the rest of the form.

**Risks**
- Ark UI's Select is uncontrolled by default; ensure controlled mode (per ADR-0002 §4 uncontrolled-input contract — but Likert is controlled, so Select should match).
- The change must preserve the data flow: `onChange(value: SurveyFlagReason | undefined)`. Don't widen the type.

---

## PR E — /compte PATCH 401 — reproduce + remediate

**Files (TBD; depends on root cause)**
Possible:
- `identity/api/src/main/kotlin/com/bliss/identity/api/auth/SessionCookies.kt` — SameSite policy change (`Lax` → `None`) if the bug is cross-origin PATCH being blocked.
- Frontend: client.ts changes only if a credential mode adjustment is needed.
- A debug-only mitigation (e.g. a Set-Cookie cleanup for the legacy `__Host-` cookie name) if the bug is stale-cookie.

**Mandatory ADR pre-read**
- ADR-0044 — Identity bounded context (the 2026-05-18 amendment on the cookie's name + posture)
- ADR-0048 — CORS wildcard predicate (PATCH preflight)
- ADR-0056 — Survey bounded context (PATCH /v1/me/preferences contract)

**Reproduction protocol (mandatory before any code edit)**
1. Use Playwright against `https://wordsparrow.io/compte` in a logged-in session.
   - If the implementer agent can't authenticate via Google OAuth headlessly, document this blocker and report back; the maintainer can paste the failing Network panel manually.
2. Open DevTools Network panel; click the "Supprimer aussi mes corrections proposées" checkbox.
3. Capture:
   - The OPTIONS preflight to `https://survey.wordsparrow.io/v1/me/preferences` — status code, request headers (`Access-Control-Request-Method`, `Access-Control-Request-Headers`), response headers (`Access-Control-Allow-Methods`, `Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials`, `Access-Control-Allow-Headers`).
   - The actual PATCH — status, request headers (especially `Cookie`), request body, response body.
4. From the `Cookie` header, determine whether the browser is sending `__Secure-ws_session` or a stale `__Host-ws_session`.
5. If the cookie is correct, check identity-api's response when survey-api proxies the verification — `kubectl logs -n wordsparrow -l app.kubernetes.io/name=bliss-identity-api --tail=80` around the same timestamp.

**Hypothesis tree (evaluate against repro)**
- (H1) Browser holds stale `__Host-ws_session` from before the rename. Fix: identity-api's `whoami` endpoint, on a request bearing the old cookie name, issues a `Set-Cookie` to clear it AND a fresh `__Secure-` cookie. Or simpler: user clears cookies manually (instruct via UI banner).
- (H2) `SameSite=Lax` cookie not sent on cross-origin same-site PATCH. Fix: change SessionCookies.kt to `SameSite=None`. Note: this requires `Secure=true` already (yes), and is the canonical posture for cross-origin authenticated APIs.
- (H3) Identity-api session-verify returns null (session invalidated by some background job, or session storage hiccup). Fix: depends on the cause.
- (H4) Some other CORS / preflight detail.

**Recommendation:** lead with H2 (SameSite=None + Secure is the right posture for a cross-origin authenticated API; Lax was probably an overcautious initial setting). Validate it against repro; if it doesn't fully explain GET working and PATCH failing, dig further.

**Success criteria**
- The exact failing request is captured + reported in the PR body (Network panel transcript or curl-equivalent).
- After the fix, the toggle works end-to-end: clicking the checkbox returns 204 No Content; the UI shows "Enregistré." with no rollback.
- `pnpm test` covers the contract; `:survey:api:test` covers the auth path.
- No regressions on /sondage's anon flow (cookie-less requests still work for the unauth GET).

**Risks**
- Cookie changes ripple through every authenticated endpoint. Test grid, game, identity-api auth flows too.
- The repro itself may be blocked (no headless Google OAuth). If so, report back; the maintainer can drive the in-browser repro.

---

## Out of scope (separately handled)

- **"Only 8 mots cycle"** — needs prod DB inspection (`kubectl exec` to a CNPG client + `SELECT count(*) FROM survey_items WHERE retired_at IS NULL GROUP BY tier;`). Maintainer can answer in 30 seconds; doesn't fit a PR-shaped task cleanly.
