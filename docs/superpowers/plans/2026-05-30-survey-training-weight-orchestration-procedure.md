# Survey training_weight — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the survey training_weight (gold-weighting Spec B) multi-PR rollout.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days; recreate if rollout exceeds 7 days).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**Spec:** `docs/superpowers/specs/2026-05-30-survey-training-weight-design.md`
**Plan:** `docs/superpowers/plans/2026-05-30-survey-training-weight.md`
**State source of truth:** `docs/superpowers/plans/2026-05-30-survey-training-weight-orchestration-log.md`.

Until this procedure file is on `origin/main`, read it (and the plan/spec) from the
bundling branch via `git show origin/docs/survey-training-weight:<path>`.

## Standing maintainer authorization

Recorded from durable in-session grants (auto-memory):

- **Merge authority (auto-merge default):** After a PR is open, the orchestrator
  merges on green blocking CI + a `LGTM` review — without asking first. Verbatim
  basis: "after opening any PR, immediately schedule a 2-min cron that merges on
  green+LGTM; don't ask first" (set 2026-05-27).
- **Cap-override (proactive):** "the 400 cap MAY be by-passed by YOUR call even
  without my call, i merged it but avoid anything like this later on" (survey-module
  rollout, 2026-05-25). The orchestrator may invoke the ADR-0001 §4 2026-05-25
  soft-target override proactively in a PR body, citing this grant, to short-circuit
  the 3c-loop-terminator. Each implementer still asks "should this split?" first; the
  override is the exception.
- **No impersonation:** the orchestrator never posts comments authored as the
  maintainer. Reviews/replies are posted as the orchestrator, citing this section.

## Phase map

Each phase leaves `./gradlew build` green on its own. No phase is stacked on another's
branch — every implementation phase bases off `main` and is gated on its dependencies
being **MERGED to main** first. B1 and B2 are the only pair safe to open in parallel
(disjoint files: an infra migration vs. a pure-domain class).

| Phase | Branch | Base | PR title prefix | Scope (plan tasks) | Precondition to dispatch |
|---|---|---|---|---|---|
| B0 | `docs/survey-training-weight` (PR #697) | `main` | `docs(survey):` | This bundle — spec + plan + procedure + log. Merges first so fresh-worktree implementers can read the plan/spec from `main`. | already open; cron only assesses + merges |
| B1 | `feat/survey-training-weight-migration` | `main` | `feat(survey-infrastructure):` | V8 migration (`survey_items.training_weight` + `maintainer_roles`) + Pg verification test (Task 1) | **B0 MERGED** |
| B2 | `feat/survey-gold-window-policy` | `main` | `feat(survey-domain):` | `GoldWindowPolicy` + 4 example tests (Task 2) | **B0 MERGED** (parallel with B1) |
| B3 | `feat/survey-training-weight-repos` | `main` | `feat(survey-infrastructure):` | `updateTrainingWeight` on the items repo (port + all impls in one commit) + `MaintainerRoleRepository` port + `PgMaintainerRoleRepository` + Pg test (Task 3) | **B1 MERGED** (needs the migration on main for its Pg test) |
| B4 | `feat/survey-recompute-use-case` | `main` | `feat(survey-application):` | `RecomputeTrainingWeightUseCase` + `InMemoryMaintainerRoleRepository` fake + 6 use-case tests (Task 4) | **B2 MERGED AND B3 MERGED** |
| B5 | `feat/survey-role-changed-consumer` | `main` | `feat(survey-infrastructure):` | `UserRoleChangedConsumer` + `UserRoleChangedConsumerConfig` + Testcontainers test (Task 5) | **B4 MERGED** (consumer ctor takes the use case) |
| B6 | `feat/survey-training-weight-wiring` | `main` | `feat(survey):` | Trigger-2 stamp + UserDeleted erasure + full api/worker/chart wiring (Task 6). **Cap-override expected.** | **B4 MERGED AND B5 MERGED** |

Notes:
- **B0 is the gate.** Dispatch B1/B2 only once B0 (PR #697) is MERGED — until then the
  plan/spec aren't on `main` and a fresh-worktree implementer can't read them. B0 has
  no implementer agent: it's already open; the cron only assesses + merges it via the
  open-PR decision tree (docs-only → merges on green + `LGTM`).
- **B1 and B2 are parallel.** Both gate on B0 only. One action per tick means they
  dispatch on consecutive ticks — that's fine; they touch disjoint files.
- **No stacking.** B3–B6 each base off `main` and wait for their deps to be MERGED, so
  there are no pre-squash-commit conflicts to rebase away. If a phase's branch somehow
  diverges, recover with a plain `git rebase origin/main`, not the onto-recipe.
- **B6 is cap-heavy.** It wires application + api + worker + chart in one coherent
  layer; splitting would leave half-wired constructors (`SubmitRatingUseCase` /
  `AnonymizeUserRatingsUseCase` ctor changes break `api/Main.kt` compilation unless they
  land together). The implementer cites the §4 soft-target override **from the first
  push**, justification "coherent integration layer; splitting leaves half-wired
  constructors that don't compile."

## Tick procedure

1. `cd "$(git rev-parse --show-toplevel)" && git fetch origin --quiet`.
2. Load this procedure + the log from `origin/main` if present, else from
   `origin/docs/survey-training-weight` via `git show`.
3. Walk the phase map in order (B0 → B1 → B2 → B3 → B4 → B5 → B6). For each phase, find
   its PR (`gh pr list --head <branch> --state all --json number,state,title`):
   - **MERGED** → move to next phase.
   - **CLOSED-not-merged** → escalate (log `ACTION`, `CronDelete` self, exit).
   - **OPEN** → assess via the open-PR decision tree below; act on the first match.
   - **No PR + this phase's precondition (above) satisfied** → dispatch the implementer
     agent for this phase (template below).
4. **Take at most one action per tick**, then stop. Be concise: one line per phase
   examined + the action taken.

### Open-PR decision tree

Apply top-down; act on the first match:

- **3a. Ready to merge.** All blocking checks `SUCCESS` AND `mergeable: MERGEABLE`
  AND `mergeStateStatus != BLOCKED` AND one of: latest review body starts with `LGTM`
  (case-insensitive) **OR** the only outstanding findings are the 400-line target AND
  the PR body cites the §4 soft-target override **OR** 3c-loop-terminator fired with an
  effectively-resolved verdict. → `gh pr merge <pr#> --squash` (no `--delete-branch` —
  it triggers a local git op that collides with agent worktrees holding `main`).
  Log `MERGED`.

  Blocking checks for this rollout:
  `build` / `submit-gradle`, `commitlint`, `branch-name`, `dco`, `gitleaks`,
  `dependency-review`. B6 also: `helm-lint` + `api-chart-lint`. No `openapi-lint` /
  `regen-and-diff` applies — Spec B touches no OpenAPI/AsyncAPI schema.
  Informational, NEVER block: `claude-review`, `CodeQL` / `Analyze (java-kotlin)`,
  `deploy` (Cloudflare preview).

- **3b. Auto-loop alive.** `claude-review` is `IN_PROGRESS`/`QUEUED`, or a
  `Claude Code Review` run on the branch fired within the last 15 min. → wait.

- **3c. Findings + no fixer activity.** Latest review starts with `Findings —`, no
  `claude-review` run active on the branch now, no commit since the review timestamp.
  - **3c-loop-terminator:** before dispatching a fixer, compare the latest review's
    first finding to the prior cycle's first finding (same rule + same location + same
    fix-shape = identical). If identical AND the diff/PR-body changed between reviews:
    - 400-line target finding → dispatch a body-edit fixer that cites the §4
      soft-target override (standing authorization above), then dispatch a fresh
      manual reviewer.
    - any other repeated finding → escalate (log `ACTION: identical finding cycles
      N-1/N on PR <pr#>; auto-loop terminated`, `CronDelete` self).
  - Else → dispatch a manual fixer (template below).

- **3d. CI complete + no review yet.** All blocking checks have a conclusion, reviews
  list empty, no `claude-review` run active. → dispatch a manual reviewer (template).

- **3e. CI still running.** Otherwise → wait.

Fix-cycle budget: **3 fixer passes per phase**; after that, escalate via log + `CronDelete`.
Review-pass cap: **5 per PR** (ADR-0001 §6a).

## Implementer agent prompt template

Dispatch with `Agent({ subagent_type: "general-purpose", isolation: "worktree", run_in_background: true, description: "<feature> <phase> · #<pr> implementer", prompt: <below> })`.

Before dispatch, run `scripts/adr-context.sh <every path the phase touches>` from the
repo root and paste its stdout into the MANDATORY READING block. The path lists per
phase:

- **B1:** `survey/infrastructure/src/main/resources/db/migration/V8__training_weight.sql survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgTrainingWeightMigrationTest.kt`
- **B2:** `survey/domain/src/main/kotlin/com/bliss/survey/domain/weight/GoldWindowPolicy.kt`
- **B3:** `survey/application/src/main/kotlin/com/bliss/survey/application/ports/SurveyItemRepository.kt survey/application/src/main/kotlin/com/bliss/survey/application/ports/MaintainerRoleRepository.kt survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgSurveyItemRepository.kt survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgMaintainerRoleRepository.kt`
- **B4:** `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/RecomputeTrainingWeightUseCase.kt`
- **B5:** `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumer.kt survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/nats/UserRoleChangedConsumerConfig.kt`
- **B6:** `survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt survey/application/src/main/kotlin/com/bliss/survey/application/usecases/AnonymizeUserRatingsUseCase.kt survey/worker/src/main/kotlin/com/bliss/survey/worker/Main.kt survey/api/deploy/chart/values.yaml survey/api/deploy/chart/templates/job-recompute-weights.yaml`

```
You are an implementation agent. **Phase <PHASE> (PR #<N>)** of the survey
training_weight (gold-weighting Spec B) rollout: <one-paragraph goal for this phase>.

## Background
Plan: `docs/superpowers/plans/2026-05-30-survey-training-weight.md` — implement **Task
<task range>** EXACTLY as written (the plan is task-by-task TDD with full code and
commands). Spec: `docs/superpowers/specs/2026-05-30-survey-training-weight-design.md`.
Follow the plan's per-task Steps (write failing test → run → implement → run → commit).

Spec B: the survey context consumes the `UserRoleChanged` event (shipped by Spec A on
subject `wordsparrow.user.role-changed`), caches the maintainer role durably, and stamps
a frozen `training_weight` onto maintainer-authored survey items created on/after the
2026-05-30 cutoff (inclusive). The weight is stored, not derived at export.

Before you begin, invoke `/jvm-backend` for backend conventions.

## MANDATORY READING — read these ADRs in full before writing any code. They are
binding rules for the paths this phase touches, not background.
<paste scripts/adr-context.sh output for this phase's paths>

## Your scope
Implement Task <range> from the plan. Exact files are listed in the plan's per-task
**Files** block. Do NOT touch files outside that set. Do NOT implement Spec C (export
reading the weight) or Spec D (corpus wiring), and make no identity-side change — out of
scope.

## How to ship
1. Branch off `origin/main` as `<branch from phase map>`.
2. Implement Task <range>, committing per the plan's Step "Commit" blocks
   (`git commit -s`, Conventional Commits, single hyphenated scope).
3. Validate locally before pushing:
   `./gradlew :survey:domain:test :survey:application:test :survey:infrastructure:test :survey:api:test --parallel`
   and `./gradlew spotlessCheck`. (B6 also: `helm lint survey/api/deploy/chart`.)
4. Push `git push -u origin <branch>`.
5. Open a PR via `mcp__github__create_pull_request` (owner `ishou`, repo `bliss`,
   base `main`). Title = `<prefix> <subject>`. Body = Why / What / Test plan,
   referencing this phase + the plan.

## Constraints
- ADR-0001 §4: 400-line soft target (generated/docs excluded). If a phase exceeds it,
  cite the §4 2026-05-25 soft-target override in the PR body FROM THE FIRST PUSH with
  justification. **B6 is expected to exceed it** — justification: "coherent integration
  layer; splitting leaves half-wired constructors that don't compile." Do not wait for
  the §6a reviewer to surface the cap.
- Conventional commits, DCO sign-off (`git commit -s`). Allowed types:
  feat fix chore refactor test docs. Single scope, no commas. Subject first word
  lowercase. Body lines ≤ 100 chars.
- No emojis. No cross-context imports (survey mirrors the `UserRoleChanged` payload
  locally — no $ref to identity). No `println`/`console.log`; structured logs only.
- Mock only at external boundaries — use the real `InMemory*` repos and a recording
  fake at the NATS boundary, never a mock of a class we wrote.

## Comment style
Comments document non-obvious WHY, in one line. Default to no comment. If you write
one, it's a single line on a non-obvious *why* — a hidden constraint, a subtle
invariant, a workaround for a specific bug. Don't explain WHAT (well-named identifiers
do that). Don't reference PRs / tasks / callers / the current fix — those rot.
Multi-paragraph comment blocks (consecutive `//` / `#`, multi-line `/* */` or `"""`)
are forbidden in new code: if you need more than one line, write the ADR and link from
one line. The auto-§6a reviewer flags this and the auto-fixer cycles otherwise — pre-empt.

## CI auto-fix loop
After pushing, monitor CI and auto-fix until green.
1. Wait ~60 s, then poll `mcp__github__pull_request_read` (`get_check_runs`) every
   ~30 s until every BLOCKING check has terminated.
   Blocking: build/submit-gradle, commitlint, branch-name, dco, gitleaks,
   dependency-review. B6 also: helm-lint + api-chart-lint.
   Informational (do NOT block/fix): claude-review, Analyze (java-kotlin)/CodeQL, deploy.
2. If a blocking check FAILED, diagnose + fix. Common: dco → `git commit -s --amend
   --no-edit; force-push`; commitlint → amend single conventional scope; build →
   reproduce with `./gradlew :survey:<module>:check` locally, fix, push; helm-lint →
   `helm lint survey/api/deploy/chart` locally.
3. Budget: 3 fix passes max. After 3, STOP and report the blocker.
4. claude-review findings: address with the same 3-pass budget.
5. Only report back once all blocking checks are green (or budget exhausted).

## Report back (under 250 words)
Branch + PR number + URL; file inventory + LOC (main vs tests); test/lint/build
outputs; decisions beyond the brief; blockers.
```

## Manual reviewer dispatch prompt

Dispatch when 3d matches (CI done, no review) or 3b hangs > 15 min.

```
You are a code reviewer. Review PR #<N> (branch `<branch>`) of the survey
training_weight rollout. Invoke the `/reviewer` skill first if available.

Read the diff via `mcp__github__pull_request_read` (`get_diff`) and the plan at
`docs/superpowers/plans/2026-05-30-survey-training-weight.md` (Task <range>) + spec.
Check: hexagonal layering (no vendor SDK in domain/application; the NATS adapter stays
in infrastructure; `GoldWindowPolicy` has zero framework deps), TDD discipline, the V8
migration is additive/backward-compatible (DEFAULT 1.0, no backfill), the consumer
mirrors `UserDeletedConsumer` (subject `wordsparrow.user.role-changed`, stream
`WORDSPARROW_USER_EVENTS`, durable `survey-api-user-role-changed`, ackWait 30s,
maxDeliver 5), the out-of-order `changed_at` guard, that `survey_items.training_weight`
survives UserDeleted while `maintainer_roles` is erased, the §4 soft-target (if the PR
body cites the override, treat the cap as resolved — do NOT re-flag it), comment style,
DCO.

Post the verdict via `gh pr review <N>` (fall back to `--comment` if `--approve` is
rejected by the same-actor token). First line MUST be either `LGTM, no findings.` or
`Findings — ` followed by a numbered list (each: rule citation + location + concrete
fix). Do NOT impersonate the maintainer. Be concise.
```

## Manual fixer dispatch prompt

Dispatch when 3c matches (findings, no fixer activity, not an identical-loop).

```
You are a fixer agent. PR #<N> (branch `<branch>`) of the survey training_weight
rollout has open review findings. Work in an isolated worktree on that branch.

Fetch the latest review via `mcp__github__pull_request_read` (`get_reviews`). Address
EACH finding with the smallest correct change. Follow the plan
(`docs/superpowers/plans/2026-05-30-survey-training-weight.md`) + spec. If a finding is
the 400-line target and the workstream is a cohesive layer (B6), update the PR body to
cite the ADR-0001 §4 2026-05-25 soft-target override + the procedure's
standing-authorization section instead of splitting.

Validate locally (`./gradlew :survey:domain:test :survey:application:test
:survey:infrastructure:test :survey:api:test --parallel spotlessCheck`, plus
`helm lint survey/api/deploy/chart` for B6), `git commit -s`, push. Then comment on the
PR mapping each finding → commit SHA. Do NOT impersonate the maintainer. Budget: 3 fix
passes. If still failing after 3, stop and report the open list.
```

## Logging format

Append one entry per action to the log file's Event log, chronological:

```
- <ISO-8601 timestamp> · <PHASE> · <PR #N or "—"> · <ACTION> — <one-line detail>
```

`ACTION` ∈ {DISPATCHED-IMPL, OPENED, DISPATCHED-REVIEWER, DISPATCHED-FIXER, WAIT,
MERGED, ESCALATED, COMPLETE}. Prefix maintainer-action items with `**ACTION:**`.

## End condition

When B6 merges:
- Append `**ACTION:** survey training_weight Spec B rollout complete. The stamped
  weights wait, unobserved, until Spec C reads them. Specs C (export reads/emits
  training_weight) and D (corpus wiring into build_modal_corpus.py) remain — separate
  spec → plan cycles.`
- `CronDelete <cron-id>`.
- Exit.
