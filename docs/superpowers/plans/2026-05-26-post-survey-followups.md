# Post-survey follow-ups (2026-05-26)

> **For agentic workers:** REQUIRED SUB-SKILL: invoke `dispatch` at session start. This plan is the canonical wave map for the post-survey follow-up workstream. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal.** Close out the user-facing and prevention follow-ups that surfaced during the 2026-05-26 survey-module prod rollout. Five independent PRs, one wave.

**Architecture.** No new abstractions, no schema changes, no cross-context refactors. Each PR is a targeted fix or prevention measure scoped to a single bounded context or shared infra.

**Tech stack.** Existing — no new dependencies expected.

---

## Wave A — five PRs in parallel

All five touch disjoint files; no schema-first dependencies. Dispatch in a single assistant turn with `isolation: "worktree"` and `run_in_background: true`.

| PR  | Title                                                                    | Context · Layer            | Approx LOC |
|-----|--------------------------------------------------------------------------|----------------------------|------------|
| α   | /confidentialite gets a sondage RGPD section                             | `frontend` · `ui`          | ~60        |
| β   | Auth-hydration flash on /sondage + /compte                               | `frontend` · `ui`          | ~80        |
| γ   | Konsist guard: install(CORS) + credentials ⇒ wildcard headers required   | shared platform arch test  | ~100       |
| δ   | Deploy workflow CORS preflight smoke (post-helm)                         | `.github/workflows/`       | ~50        |
| ε   | Survey IdentityClient cookie name `__Host-` → `__Secure-ws_session`      | `survey` · `infrastructure`| ~15        |

---

## PR α — `/confidentialite` sondage RGPD section

**Files**
- Modify: `frontend/src/ui/components/PrivacyNotice.tsx` (the page content)
- Modify (if tested): `frontend/tests/privacy-notice.test.tsx` or similar

**Mandatory ADR pre-read (run `scripts/adr-context.sh` for actual bodies)**
- ADR-0025 — Matomo + RGPD
- ADR-0044 — Identity bounded context (WP216 anonymization context)
- ADR-0045 — Player identity data minimization
- ADR-0050 — Accessibility (page must remain WCAG AA)
- ADR-0056 — Survey bounded context (the RGPD posture is documented here)

**Spec**
- Add a section after the existing privacy content with French copy explaining:
  - Survey ratings (notes, optional `correctif`) collected with anonymous sessions by default; authenticated mode also supported.
  - On account deletion (WP216 anonymization per ADR-0045), the user's ratings are *kept but anonymized*, not deleted. Proposed clue text (`correctif`) is also kept by default; users may opt to delete their corrections via `/compte`'s "Supprimer aussi mes corrections proposées" toggle (the existing `deleteProposedOnErasure` preference).
  - Link to the existing `/compte` toggle.
- Maintain WCAG AA: section uses a proper `<h2>`/`<h3>` hierarchy, focusable links, no colour-only emphasis.

**Success criteria**
- Frontend `pnpm test` passes (no new tests required if the page has no existing test, but if there is one, update it to cover the new section).
- `pnpm a11y` passes against `/confidentialite`.
- Manual visual check: copy reads correctly and links work.

**Risks**
- French copy needs to match the actual implementation (anonymisation vs anonymization spelling — use French `anonymisation`). Don't promise behaviour that doesn't exist; verify the toggle key and behaviour against `SurveyPreferences.tsx` + the survey/api `mePreferencesRoute.kt`.

---

## PR β — auth-hydration flash on `/sondage` + `/compte`

**Files**
- Modify: `frontend/src/ui/routes/sondage.tsx` (eager half — `beforeLoad`/`pendingComponent`)
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx` (gating on `state.status === 'loading'`)
- Modify: `frontend/src/ui/routes/compte.tsx` (same shape — auth state hydration)
- Possibly: `frontend/src/ui/components/auth/AuthProvider.tsx` if the gate needs a "ready" signal

**Mandatory ADR pre-read**
- ADR-0002 — frontend stack (TanStack Router conventions)
- ADR-0044 — Identity context (whoami endpoint contract)
- ADR-0050 — Accessibility (no jarring content shifts)
- ADR-0054 — Page shell primitive

**Symptom (verify with Playwright before designing the fix)**
- Visiting https://wordsparrow.io/sondage flashes home page layout → logged-out `/sondage` (SignInBanner) → correct page state, all within ~500ms when authenticated.
- Visiting https://wordsparrow.io/compte while authenticated flashes "Chargement…" then redirects to `/` before settling on the authenticated `/compte` content.

**Reproduction protocol (the implementer MUST do this BEFORE editing)**
1. Use Playwright against the live `https://wordsparrow.io` (not the Pages preview URL).
2. Capture `browser_snapshot` at intervals during navigation to /sondage and /compte (use `wait_for time=0.2`, `=0.5`, `=1.0`, `=2.0` to characterise the sequence).
3. Inspect `browser_network_requests` to see when `auth.wordsparrow.io/v1/auth/whoami` resolves relative to the route's first paint.
4. Document the actual render-vs-hydration order before designing the fix.

**Likely fix shape (validate against repro before committing)**
- Route's `beforeLoad` awaits `useAuth()`'s ready state (or the route uses TanStack's `pendingMs` + `pendingComponent` to render a stable skeleton until auth resolves).
- Match the pattern grille.tsx already uses for `pendingMs: 200`.

**Success criteria**
- Repro is documented in the PR body.
- After the fix, snapshots at 50/200/500/1000 ms during `/sondage` and `/compte` navigation show ONE rendered state (skeleton or final), never a transient logged-out/wrong state.
- `pnpm test`, `pnpm typecheck`, `pnpm a11y` pass.
- Manual smoke: navigate /sondage and /compte; no flash visible to a human at 60 Hz.

**Risks**
- Easy to introduce a different flash (a blank skeleton lingering too long; or worse, breaking the unauthenticated-by-design /sondage path where the SignInBanner is the legitimate render). Test BOTH authenticated and unauthenticated cases.
- Don't accidentally block render on whoami when the route should render the anon path immediately.

---

## PR γ — Konsist guard: credentialed CORS requires wildcard headers

**Files**
- Create: a Konsist architecture test that scans every `*/api/src/main/kotlin/**/Module.kt` (and helper files extracted from them) for the pattern.
- Likely under `:platform:architecture-tests` if such a shared module exists, otherwise duplicated per api module (mirror `*ArchitectureTest.kt` placement).

**Mandatory ADR pre-read**
- ADR-0034 — CORS allow-any-header (grid/game origin)
- ADR-0048 — CORS wildcard predicate for credentialed contexts (identity)

**Spec**
- A test that fails any Kotlin source under `*/api/src/main/kotlin/**` that:
  1. Calls `install(CORS) { ... }` (block form OR an extracted helper invoked from `Application.module()`), AND
  2. The block (or helper body) sets `allowCredentials = true`, AND
  3. Does NOT call `allowHeaders { true }` (the wildcard predicate).
- The rule must catch BOTH the inline `install(CORS) { … }` form (current grid/game/identity) AND the extracted-function form (survey-api's `Application.installSurveyCors(config)` introduced by PR #636).
- Failure message references ADR-0048 + the 2026-05-26 5th-CORS regression.

**Success criteria**
- Test PASSES against the current main (all four api modules already use `allowHeaders { true }` after PR #636 landed).
- Test FAILS if you locally revert PR #636's wildcard change as a sanity check.
- Konsist test runs as part of `:survey:api:test` (or wherever it's placed).

**Risks**
- Konsist's API for matching multi-statement blocks may not natively support this rule shape. Two fallback approaches: (a) parse the file as text and grep with a regex (looser but works), (b) Konsist's `kotlinFiles().withFile { it.text.contains(...) }` for textual matching. Either is acceptable — prefer the structural Konsist API if it cleanly expresses the rule.
- DO NOT modify the existing api Module.kt files. The test must pass against current state.

---

## PR δ — deploy workflow CORS preflight smoke

**Files**
- Modify: `.github/workflows/deploy-api-k8s.yml`

**Mandatory ADR pre-read**
- ADR-0009 — k3s self-managed deployment
- ADR-0034 — CORS allow-any-header
- ADR-0048 — CORS wildcard predicate

**Spec**
- After the existing "Helm upgrade --install (api chart)" step and the post-deploy health check, add a new step "CORS preflight smoke":
  - For the bounded context being deployed, derive the public ingress host (e.g. `survey.wordsparrow.io` for `context=survey`, `auth.wordsparrow.io` for `identity`, etc.). Mapping should live in the workflow as a small case/lookup.
  - `curl -sS -o /dev/null -w '%{http_code} %{header_json}' -X OPTIONS https://<host>/<one-existing-endpoint> -H 'Origin: https://wordsparrow.io' -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: traceparent'`
  - Assert HTTP 200 AND that the response includes `access-control-allow-origin: https://wordsparrow.io` AND `access-control-allow-headers` containing `traceparent`.
- Step retries with 5s backoff for up to 60s to handle ingress propagation timing.
- Step is REQUIRED (failing it fails the deploy) for the api-chart deploy contexts; not for the worker-only contexts.

**Success criteria**
- yaml-lint clean (`python3 -c "import yaml; yaml.safe_load(open(...))"`).
- Step renders correctly when manually triggered with `gh workflow run`.
- Re-runs of the workflow against the currently-deployed contexts pass the smoke.
- A simulated failure (temporarily reverting a Module.kt to drop `allowHeaders { true }`) would cause the deploy to fail at this step — verify by reading the logic, not by actually breaking prod.

**Risks**
- The endpoint must exist on every context. Use a stable health-check or a known unauthenticated endpoint per context (e.g. `/v1/health`).
- Don't block on this in non-deploy contexts (the workflow's other jobs shouldn't depend on this step).
- Beware command injection — use env vars, not direct interpolation of GitHub-event fields (per the workflow-edit security guidance).

---

## PR ε — Survey IdentityClient cookie name fix

**Files**
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/identity/IdentityClient.kt:62`
- Modify: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/identity/IdentityClientTest.kt:37`

**Mandatory ADR pre-read**
- ADR-0044 — Identity bounded context (esp. the 2026-05-18 amendment naming the cookie `__Secure-ws_session`)

**Spec**
- Change `IdentityClient.kt:62` from `const val SESSION_COOKIE_NAME: String = "__Host-ws_session"` to `"__Secure-ws_session"`.
- Update the existing test in `IdentityClientTest.kt:37` from asserting `__Host-ws_session=session-cookie-value` to `__Secure-ws_session=session-cookie-value`.
- Verify against the truth in `identity/api/src/main/kotlin/com/bliss/identity/api/auth/SessionCookies.kt:13` (`const val NAME = "__Secure-ws_session"`).

**Success criteria**
- `:survey:infrastructure:test --tests *IdentityClient*` passes.
- Authenticated survey-api routes (`/v1/me/contributions`, `PATCH /v1/me/preferences`) no longer 401 in prod after deploy.

**Risks**
- The test was wrong in the same direction as the constant — don't just align them with each other, align with identity-api's truth.
- Verify there are no other places in the survey codebase that hardcode `__Host-ws_session` (one more grep).

---

## Out of scope

- Filter6 alignment with style guide §6.2 — known deviation but no production symptom; not in this wave (decided 2026-05-26).
- Any new ADRs — none of these PRs introduce architectural decisions.
- /sondage UX redesign — the flash is the only user-visible issue right now.

## After this wave merges

- Deploy the survey context once more (manual trigger of `deploy-api-k8s.yml context=survey`) so ε reaches prod and authenticated routes actually work.
- Manually verify /sondage + /compte are flash-free at 60 Hz.
- Move on to whatever the maintainer prioritises next.
