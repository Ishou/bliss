# /sondage simplification — GOOD / BAD / SKIP (2026-05-26)

> **For agentic workers:** REQUIRED SUB-SKILL: invoke `dispatch` at session start. This plan is the canonical wave map for the /sondage simplification workstream. Steps use checkbox (`- [ ]`) syntax for tracking.

## Goal

Replace the current `/sondage` RatingCard's two Likert scales (qualité + difficulté) + FlagPicker + correctif textarea with a 3-button verdict picker: **GOOD / BAD / SKIP**.

This is a single frontend PR with no schema or server changes — see "Scope honesty" below.

## Rationale

For solo round-0 rating (single rater is the maintainer; selected algorithm is RAFT-not-DPO per the parallel clue-loop plan), the Likert UI's granularity is mostly noise. Binary verdict aligns natively with RAFT (`qualite = 5` is the winners filter). Fatigue drops 2-3× per rating. Difficulty rating loses meaning when the clue is BAD anyway. Correctif and flag are heavy UI for round-0 and re-introducible later when multi-rater calibration becomes relevant.

The third option (SKIP) is not strict-binary on purpose:
- Defers judgement on edge cases without forcing a binary commit.
- Skipped clues never enter `winners` (RAFT keeps `GOOD` only).
- A high SKIP rate is a diagnostic signal the generator is producing ambiguous outputs.

## Scope honesty (decision recorded, not just executed)

The "clean" shape would be a 3-PR wave following ADR-0001 §3 schema-first workflow:
- α — schema-only PR: `RatingSubmission.difficulte` becomes optional in `survey/api/openapi.yaml`.
- β — server: V5 migration making `difficulte` nullable, route relaxation.
- γ — frontend: RatingCard rewrite.

**This plan does NOT take that shape.** Trade-off:
- Schema-clean (3 PRs, ~300 LOC, ADR-0001 §3 compliant, clean DB) vs
- Frontend-only (1 PR, ~200 LOC, sends `difficulte=3` placeholder, dummy data filterable by `created_at` cutoff).

The schema-clean shape is ~3× procedural overhead for what's cosmetic data hygiene. The dummy `difficulte=3` data is filterable, RAFT extraction doesn't read `difficulte`, and the column can be cleaned later as a follow-up if it bothers anyone. The frontend-only path lets us start the loop sooner.

**Decision:** ship the frontend-only path. Schema cleanup deferred to its own PR if/when desired. Document the placeholder behaviour in the PR body so future analysis filters `difficulte != 3 OR created_at < '2026-05-27'`.

ADR-0001 §3 is binding for *cross-context* schema changes. Survey has one consumer (frontend). Reasonable judgment call.

## Sequencing

This PR rewrites `frontend/src/ui/components/sondage/RatingCard.tsx` heavily. The in-flight UX wave (PRs #649, #650, #651, #652, plus B's prerender PR) also touches `RatingCard.tsx` (PR #649 added French labels). **This wave dispatches only AFTER the UX wave fully merges** to avoid rebase pain.

The orchestrator's tick procedure checks the UX wave's merge status before dispatching this PR's implementer.

---

## Wave — single PR

| PR | Title                                                                       | Context · Layer    | Approx LOC |
|----|-----------------------------------------------------------------------------|--------------------|------------|
| α  | RatingCard: GOOD / BAD / SKIP verdict picker; defer difficulté + flag + correctif | `frontend` · `ui` | ~200       |

---

## PR α — RatingCard verdict picker

**Files**
- Modify: `frontend/src/ui/components/sondage/RatingCard.tsx` (heavy rewrite)
- Modify: `frontend/src/ui/components/sondage/index.ts` (drop FlagPicker re-export if no longer used elsewhere)
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx` (the `onSubmit` payload shape changes — see below)
- Modify: `frontend/src/application/survey/` (the rating submission DTO — if any client-side type expects the old shape)
- Delete: `frontend/src/ui/components/sondage/FlagPicker.tsx` (its Ark UI Select primitive lives at `frontend/src/ui/components/primitives/Select.tsx` and stays — keep that)
- Modify: existing RatingCard / FlagPicker tests; add new `sondage-verdict-picker.test.tsx` or extend the rating-card test

**Mandatory ADR pre-read (run `scripts/adr-context.sh`)**

```sh
scripts/adr-context.sh \
  frontend/src/ui/components/sondage/RatingCard.tsx \
  frontend/src/ui/components/sondage/FlagPicker.tsx \
  frontend/src/ui/routes/sondage.lazy.tsx \
  frontend/src/application/survey/
```

Read every ADR body in full. Especially ADR-0002 (frontend stack), ADR-0050 (a11y — the new buttons MUST keep keyboard nav + screen-reader semantics), ADR-0056 (survey context — the rating contract).

**Spec — UI**

Replace the Likert blocks (qualité 1-5 + difficulté 1-5) with three large, equally-weighted buttons:

```
┌──────────┬──────────┬──────────┐
│   BAD    │   SKIP   │   GOOD   │
│  (red)   │ (muted)  │ (green)  │
└──────────┴──────────┴──────────┘
```

Each button submits immediately on click — no separate "submit" button. The UX matches Tinder-style high-throughput rating: see clue → tap one of three → next clue auto-loads.

- Button order LEFT → RIGHT: BAD, SKIP, GOOD. (Red on left matches "destructive on left" convention common in French UX.)
- Keyboard shortcuts: `j` = BAD, `k` = SKIP, `l` = GOOD (matches vim/Tinder muscle memory; documented in a small tooltip).
- ARIA: each button has `aria-label="<verdict-label> for clue '<clue>'"`. Use `role="group"` on the wrapper with `aria-label="Verdict"`.
- The chips (pos / categorie / style + difficulté annoncée line) stay — PR #649 already made them human-French.
- The FlagPicker, the correctif textarea, and the submit button are REMOVED from the UI.

**Spec — submission shape**

The existing `RatingSubmission` type has `qualite`, `difficulte`, `flag?`, `correctif?`. Map the new verdicts to the existing wire format:

| Verdict | Submit? | qualite | difficulte | flag | correctif |
|---------|---------|---------|------------|------|-----------|
| GOOD    | yes     | 5       | 3 (placeholder) | omit | omit |
| BAD     | yes     | 1       | 3 (placeholder) | omit | omit |
| SKIP    | NO      | —       | —          | —    | —    |

SKIP advances the client to the next item WITHOUT calling `surveyClient.submitRating`. To avoid re-seeing the same item next round, add the item to the local `surveyAnonStore` (anon) or maintain a session-scoped skipped-ids set (auth). Skipped items are NOT stored server-side in round-0; if multi-session skip persistence becomes important, add it as a follow-up.

**Spec — analytics**

Replace the current rating analytics events with:
- `survey/verdict_submitted` — `tier=<tier>;verdict=<GOOD|BAD>`.
- `survey/verdict_skipped` — `tier=<tier>`.

Drop `correctif_proposed` and `flag_raised` events (no longer reachable).

**Success criteria**

- The three verdict buttons render side-by-side. Each is at least 56×56 px (a11y minimum touch target per ADR-0050).
- Keyboard shortcuts `j/k/l` work when the rating card is the focused region (not when typing in an input — but no inputs remain on this surface).
- Submitting GOOD or BAD calls `surveyClient.submitRating` with `{ qualite: 5|1, difficulte: 3 }`.
- Submitting SKIP advances locally WITHOUT a network call.
- After any verdict, the next item loads (existing behaviour).
- `pnpm test`, `pnpm typecheck`, `pnpm lint`, `pnpm a11y` all pass.
- Manual smoke against the deployed frontend: rate 5 clues GOOD/BAD/SKIP in mixed order; verify network panel shows 2 POSTs (skip is local).

**Risks**

- **Hidden coupler with backend.** Confirm by reading `survey/api/src/main/kotlin/com/bliss/survey/api/routes/ratingsRoute.kt` that `difficulte=3` IS accepted (validation rule: `BETWEEN 1 AND 5` per V2 migration). If the server enforces a stricter rule downstream, the placeholder fails — flag in PR body if so.
- **FlagPicker deletion ripples.** Search for `FlagPicker` imports across `frontend/src/`. The Ark UI Select primitive (PR #652) is a separate file at `frontend/src/ui/components/primitives/Select.tsx` and is NOT deleted.
- **Test churn.** Several existing tests assert the old Likert/FlagPicker UI shape. Rewrite or delete them; don't keep dead-asserts.
- **K-coverage policy interaction.** The `qualite=5` winners filter (used by the parallel `extract_winners.py`) assumes GOOD always maps to 5 — if a future iteration adds a 4-option scale (e.g., GREAT/GOOD/BAD/AWFUL), the filter and this mapping must update together.

**Comments style**: one-line non-obvious why only. NO multi-paragraph docstrings, NO multi-line comment blocks.

---

## Out of scope

- V5 schema migration making `difficulte` nullable (deferred — see "Scope honesty").
- OpenAPI change to `RatingSubmission.difficulte` (deferred for the same reason).
- A `verdict` enum column on `ratings` (deferred indefinitely — `qualite={1,5}` mapping is durable).
- Multi-session skip persistence (deferred until round-2+ if needed).
- `/sondage/duels` pairwise palier (deferred — RAFT signal from the verdict picker is sufficient for now).
- Re-introducing correctif as a "BAD — and here's why" follow-up flow (deferred to a later wave).

## Dispatch checklist

- [ ] UX wave fully merged on `main` (PRs #649, #650, #651, #652, #B-prerender).
- [ ] Branch `feat/frontend-sondage-verdict-picker` does not exist on `origin` yet.
- [ ] Agent prompt pastes the entire PR α section above (file list / ADR pre-read / spec / success criteria / risks).
- [ ] Agent prompt sets the `frontend` domain skill.
- [ ] Agent prompt budgets 3 auto-fix passes max.

## Schema cleanup (deferred — separate small PR if/when wanted)

When the dummy `difficulte=3` data accumulates and analysis filtering becomes annoying:

1. V5 Flyway migration: `ALTER TABLE ratings ALTER COLUMN difficulte DROP NOT NULL;`.
2. OpenAPI: `RatingSubmission.difficulte` → optional.
3. Survey-api route: accept null; the frontend's `difficulte: 3` becomes `difficulte: undefined`.

~50 LOC total. Standalone PR. NOT part of this plan.
