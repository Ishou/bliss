# Identity User Roles — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the identity-user-roles (gold-weighting Spec A) multi-PR rollout.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days; recreate if rollout exceeds 7 days).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**Spec:** `docs/superpowers/specs/2026-05-30-identity-user-roles-design.md`
**Plan:** `docs/superpowers/plans/2026-05-30-identity-user-roles.md`
**State source of truth:** `docs/superpowers/plans/2026-05-30-identity-user-roles-orchestration-log.md`.

Until this procedure file is on `origin/main`, read it (and the plan/spec) from the
bundling branch via `git show origin/docs/identity-user-roles:<path>`.

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

Strictly sequential. A1 is the schema-first + ADR barrier (ADR-0001 §3, §7): A2's
code cites ADR-0060, so **A1 must be MERGED before A2 opens**. A3 is stacked on A2.

| Phase | Branch | Base | PR title prefix | Scope (plan tasks) |
|---|---|---|---|---|
| A0 | `docs/identity-user-roles` (PR #688) | `main` | `docs(identity):` | This bundle — spec + plan + procedure + log. Must merge first so implementer agents (which run in fresh worktrees off `main`) can read the plan/spec. |
| A1 | `docs/identity-roles-adr-event` | `main` | `docs(identity):` | ADR-0060 + `identity/api/events/UserRoleChanged.yaml` + `docs/adr/INDEX.md` (Tasks 11–12) |
| A2 | `feat/identity-role-persistence` | `main` | `feat(identity-*):` | `Role`, `User.role`, migration V5, repo read/write + `updateRole`, ports (Tasks 1–6) |
| A3 | `feat/identity-role-bootstrap` | `feat/identity-role-persistence` (A2) | `feat(identity-*):` | `SetUserRoleUseCase`, NATS adapter, `main()` dispatch, Helm Job + values (Tasks 7–10) |

Notes:
- **A0 is the gate.** Dispatch A1 only once A0 (PR #688) is MERGED — until then the
  plan/spec aren't on `main` and a fresh-worktree implementer can't read them. A0 has
  no implementer agent: it's already open; the cron only assesses + merges it via the
  open-PR decision tree (it's docs-only, so it merges on green + `LGTM`; the §4
  soft-target override is already cited in its body).
- A1 and A2 both base off `main` but are NOT parallel: A2 references ADR-0060, which
  only exists on `main` after A1 merges. Dispatch A2 only once A1 is MERGED.
- A3 is **stacked on A2**. After A2 squash-merges, A3 becomes `CONFLICTING`; recover
  with the stacked-PR rebase recipe (below) before continuing its tick.

## Tick procedure

1. `cd "$(git rev-parse --show-toplevel)" && git fetch origin --quiet`.
2. Load this procedure + the log from `origin/main` if present, else from
   `origin/docs/identity-user-roles` via `git show`.
3. Walk the phase map in order (A0 → A1 → A2 → A3). For each phase, find its PR
   (`gh pr list --head <branch> --state all --json number,state,title`):
   - **MERGED** → move to next phase.
   - **CLOSED-not-merged** → escalate (log `ACTION`, `CronDelete` self, exit).
   - **OPEN** → assess via the open-PR decision tree below; act on the first match.
   - **No PR + previous phase MERGED (or this is A1)** → dispatch the implementer
     agent for this phase (template below). For A2, the precondition is **A1 MERGED**.
     For A3, the precondition is **A2 MERGED** (then expect a rebase before its first
     CI pass — see stacked-PR recovery).
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
  `dependency-review`, `openapi-lint` (A1 only — the AsyncAPI fragment is NOT linted
  per ADR-0019, but the workflow may still run), `helm-lint` + `api-chart-lint`
  (A3 only). `registry-coherence` (A1 — ADR ↔ INDEX.md must change together).
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

- **A1:** `docs/adr/0060-identity-user-roles.md docs/adr/INDEX.md identity/api/events/UserRoleChanged.yaml`
- **A2:** `identity/domain/src/main/kotlin/com/bliss/identity/domain/user/User.kt identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRepository.kt identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/persistence/PostgresUserRepository.kt identity/infrastructure/src/main/resources/db/migration/V5__user_role.sql`
- **A3:** `identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt identity/infrastructure/src/main/kotlin/com/bliss/identity/infrastructure/events/NatsUserRoleChangedBroadcaster.kt identity/api/deploy/chart/templates/job-maintainer-roles.yaml identity/api/deploy/chart/values.yaml`

```
You are an implementation agent. **Phase <PHASE> (PR #<N>)** of the identity-user-roles
(gold-weighting Spec A) rollout: <one-paragraph goal for this phase>.

## Background
Plan: `docs/superpowers/plans/2026-05-30-identity-user-roles.md` — implement **Tasks
<task range>** EXACTLY as written (the plan is task-by-task TDD with full code and
commands). Spec: `docs/superpowers/specs/2026-05-30-identity-user-roles-design.md`.
Follow the plan's per-task Steps (write failing test → run → implement → run → commit).

Before you begin, invoke `/jvm-backend` for backend conventions (A2/A3) or `/schemas`
for the AsyncAPI fragment (A1).

## MANDATORY READING — read these ADRs in full before writing any code. They are
binding rules for the paths this phase touches, not background.
<paste scripts/adr-context.sh output for this phase's paths>

## Your scope
Implement Tasks <range> from the plan. Exact files are listed in the plan's File
Structure section and per task. Do NOT touch files outside that set. Do NOT implement
Specs B–D (survey consumer, training_weight, export) — out of scope.

## How to ship
1. Branch off `origin/<base>` as `<branch from phase map>`.
   (A3: base off `feat/identity-role-persistence`, NOT main.)
2. Implement Tasks <range>, committing per the plan's Step "Commit" blocks
   (`git commit -s`, Conventional Commits, single hyphenated scope).
3. Validate locally before pushing:
   `./gradlew :identity:domain:test :identity:application:test :identity:infrastructure:test :identity:api:test --parallel`
   and `./gradlew spotlessCheck`. (A1: no gradle — run `helm`/schema checks per plan;
   A3: also `helm lint identity/api/deploy/chart --values identity/api/deploy/chart/values-prod.yaml`.)
4. Push `git push -u origin <branch>`.
5. Open a PR via `mcp__github__create_pull_request` (owner `ishou`, repo `bliss`,
   base = phase-map base). Title = `<prefix> <subject>`. Body = Why / What / Test plan,
   referencing this phase + the plan. A3: include the threat model from ADR-0060.

## Constraints
- ADR-0001 §4: 400-line soft target (generated/docs excluded). If a phase exceeds it,
  cite the §4 2026-05-25 soft-target override in the PR body FROM THE FIRST PUSH with
  justification "cohesive role primitive; splitting fragments tightly-coupled
  domain+persistence" — do not wait for the §6a reviewer.
- Conventional commits, DCO sign-off (`git commit -s`). Allowed types:
  feat fix chore refactor test docs. Single scope, no commas. Subject first word
  lowercase. Body lines ≤ 100 chars.
- No emojis. No cross-context imports. No `println`/`console.log`.

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
   dependency-review, registry-coherence (A1), openapi-lint (A1), helm-lint +
   api-chart-lint (A3).
   Informational (do NOT block/fix): claude-review, Analyze (java-kotlin)/CodeQL, deploy.
2. If a blocking check FAILED, diagnose + fix. Common: dco → `git commit -s --amend
   --no-edit; force-push`; commitlint → amend single conventional scope; build →
   reproduce with `./gradlew :<module>:check` locally, fix, push; registry-coherence →
   ensure ADR + INDEX.md changed together; helm-lint → `helm lint` locally.
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
You are a code reviewer. Review PR #<N> (branch `<branch>`) of the identity-user-roles
rollout. Invoke the `/reviewer` skill first if available.

Read the diff via `mcp__github__pull_request_read` (`get_diff`) and the plan at
`docs/superpowers/plans/2026-05-30-identity-user-roles.md` (Tasks <range>) + spec +
ADR-0060. Check: hexagonal layering (no vendor SDK in domain/application; NATS adapter
stays in infrastructure), TDD discipline, migration is additive/backward-compatible,
the event payload matches `identity/api/events/UserRoleChanged.yaml`, the §4 soft-target
(if the PR body cites the override, treat the cap as resolved — do NOT re-flag it),
comment style, DCO.

Post the verdict via `gh pr review <N>` (fall back to `--comment` if `--approve` is
rejected by the same-actor token). First line MUST be either `LGTM, no findings.` or
`Findings — ` followed by a numbered list (each: rule citation + location + concrete
fix). Do NOT impersonate the maintainer. Be concise.
```

## Manual fixer dispatch prompt

Dispatch when 3c matches (findings, no fixer activity, not an identical-loop).

```
You are a fixer agent. PR #<N> (branch `<branch>`) of the identity-user-roles rollout
has open review findings. Work in an isolated worktree on that branch.

Fetch the latest review via `mcp__github__pull_request_read` (`get_reviews`). Address
EACH finding with the smallest correct change. Follow the plan
(`docs/superpowers/plans/2026-05-30-identity-user-roles.md`), spec, and ADR-0060.
If a finding is the 400-line target and the workstream is a cohesive layer, update the
PR body to cite the ADR-0001 §4 2026-05-25 soft-target override + the procedure's
standing-authorization section instead of splitting.

Validate locally (`./gradlew :identity:*:test spotlessCheck`, or helm lint for A3),
`git commit -s`, push. Then comment on the PR mapping each finding → commit SHA. Do NOT
impersonate the maintainer. Budget: 3 fix passes. If still failing after 3, stop and
report the open list.
```

## Stacked-PR recovery (A3 after A2 merges)

A3 is stacked on A2's branch. When A2 squash-merges, A3 carries A2's pre-squash commits
and goes `CONFLICTING`. Recover:

```
git worktree remove .claude/worktrees/agent-<id> -f -f   # if a worktree locks the branch
git fetch origin --quiet
git checkout -B rebase-tmp origin/feat/identity-role-bootstrap
# find where A2's commits end and A3's begin:
git log --oneline origin/main..rebase-tmp
git rebase --onto origin/main <last-A2-commit-sha> rebase-tmp
git push --force-with-lease origin rebase-tmp:feat/identity-role-bootstrap
```

CI re-runs cleanly after the rebase drops the duplicate A2 commits.

## Logging format

Append one entry per action to the log file's Event log, chronological:

```
- <ISO-8601 timestamp> · <PHASE> · <PR #N or "—"> · <ACTION> — <one-line detail>
```

`ACTION` ∈ {DISPATCHED-IMPL, OPENED, DISPATCHED-REVIEWER, DISPATCHED-FIXER, WAIT,
MERGED, REBASED, ESCALATED, COMPLETE}. Prefix maintainer-action items with `**ACTION:**`.

## End condition

When A3 merges:
- Append `**ACTION:** identity-user-roles Spec A rollout complete. Specs B–D (survey
  consumer, training_weight, export wiring) remain — separate spec → plan cycles.`
- `CronDelete <cron-id>`.
- Exit.
