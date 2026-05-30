# Survey Rating Undo — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the survey rating-undo multi-PR rollout.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; session-only in practice — recreate if the rollout outlives the session).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**State source of truth:** `docs/superpowers/plans/2026-05-30-survey-undo-orchestration-log.md`.

**Spec:** `docs/superpowers/specs/2026-05-30-survey-undo-design.md`.
**Plan:** `docs/superpowers/plans/2026-05-30-survey-rating-undo.md`.

The dispatch skill (`.claude/skills/dispatch/SKILL.md`) is the canonical playbook. This file instantiates it for this rollout. The dispatch skill overrides anything here that conflicts.

## Standing maintainer authorization (recorded 2026-05-30)

- **Merge authority:** Granted in-session. The maintainer invoked `/orchestrate` in direct response to an offer of "hands-off cron-driven dispatch + auto-merge." The orchestrator merges a phase PR on green blocking CI + `LGTM` first-line review (standard 3a). No per-PR maintainer confirmation required for this rollout.
- **400-line cap override:** Standing authorization (memory `feedback-standing-cap-override`, `feedback-cap-override-short-circuit`). The cap is a "should we split?" trigger, not a hard gate. The orchestrator may invoke the ADR-0001 §4 2026-05-25 soft-target override **proactively, without escalating**, to short-circuit the 3c-loop-terminator. PR 2c (undo + wiring) is pre-flagged cap-heavy; its implementer cites the soft-target override in the PR body from the first push.
- **No impersonation:** The orchestrator never posts `@maintainer`-authored comments. It posts as the orchestrator and cites this section.

## Bootstrap gate (Phase 0)

This rollout's spec + plan + procedure + log ship in **one bootstrap docs PR** off `main` (this branch: `docs/survey-undo-orchestration`). Per memory `feedback-bootstrap-pr-uses-3a`, the bootstrap PR merges via the **standard 3a path** — no special Wave-0 gate.

**Phase 1 must NOT dispatch until the bootstrap PR is merged to `main`**, because the Phase 1 implementer (working in a worktree off `origin/main`) reads the plan file from `main`. Until then:

- On each tick, if the bootstrap PR is still **open**, apply the open-PR decision tree to it (review → merge) exactly as for a feature phase.
- Once the bootstrap PR is **merged**, proceed to the Phase 1 dispatch logic.

## Phase map

Strictly sequential. Each phase branches off `origin/main` **after its predecessor merges to `main`** (no stacking — the predecessor's types/helpers must be on `main` first).

| Phase | Branch | Base | PR title prefix | Depends on |
|---|---|---|---|---|
| 0 — bootstrap docs | `docs/survey-undo-orchestration` | `main` | `docs(survey):` | — |
| 0.5 — ADR-0059 amendment | `docs/survey-undo-adr-amendment` | `main` | `docs(adr):` | Phase 0 merged |
| 1 — schema-only | `feat/survey-undo-schema` | `main` | `feat(api-survey):` | Phase 0.5 merged |
| 2a — tx boundary | `refactor/survey-tx-boundary` | `main` | `refactor(survey-infrastructure):` | Phase 1 merged |
| 2b — action log | `feat/survey-action-log` | `main` | `feat(survey-infrastructure):` | Phase 2a merged |
| 2c — undo + wiring | `feat/survey-undo-usecase` | `main` | `feat(survey):` | Phase 2b merged |
| 3 — frontend | `feat/survey-frontend-undo` | `main` | `feat(survey-frontend):` | Phase 1 merged (needs `pnpm api:generate`) **and** Phase 2c merged (server returns `undoToken` / honours undo) |

Phase scopes (from the plan):
- **0.5:** `docs/adr/0059-*.md` amendment — undo window = campaign lifetime + 8 s grace, export settles per-campaign-at-close, `proposed_item_id` recipe column rationale; `docs/adr/INDEX.md` new use-case path entries. Docs-only. (Plan Phase 0.5.)
- **1:** `survey/api/openapi.yaml` — additive `undoToken` on `RatingResponse`/`PairRatingResponse`, `POST /v1/actions/undo` + `UndoActionRequest`. (Plan Phase 1.)
- **2a:** `TransactionManager` port + `PgTransactionManager` + `TxConnection` ambient-connection element; refactor the 5 write repos to `withTxConnection`. No behavior change. (Plan Phase 2.)
- **2b:** V8 migration `survey_actions`; `SurveyAction` domain; `TokenGenerator` port + `UndoTokenHash` util; `ActionLogRepository` port + `PgActionLogRepository`; new reversal repo methods. (Plan Phase 3.)
- **2c:** `UndoActionUseCase`; submit use-cases mint token + write recipe in a transaction; export settling filter; `POST /v1/actions/undo` route; DI wiring. (Plan Phase 4.)
- **3:** regenerate types; client `undoAction` + `undoToken`; anon-store `remove`; `UndoBar`; on-card undo on both sondage routes; analytics; vitest/MSW tests. (Plan Phase 5.)

## Tick procedure

Run from repo root. Take **at most one action per tick**, then stop. Be concise — one line per phase examined plus the action taken.

1. `cd "$(git rev-parse --show-toplevel)" && git fetch origin --quiet`.
2. Read this procedure. If it is not on `origin/main` yet, you are already reading it from `origin/docs/survey-undo-orchestration` via the bootstrap prompt — proceed.
3. **Bootstrap gate:** locate the bootstrap PR (head branch `docs/survey-undo-orchestration`). If it exists and is **open**, apply the open-PR decision tree to it and stop. If it is **closed-not-merged**, escalate (log `ACTION` + `CronDelete` self + exit). If **merged**, continue.
4. Walk the phase map (0.5 → 1 → 2a → 2b → 2c → 3) in order. For the first phase not yet merged:
   - **No PR for this phase AND all dependencies merged** → dispatch the implementer agent for this phase (template below). Log `DISPATCHED`. Stop.
   - **No PR AND a dependency is not yet merged** → wait (a predecessor is still in flight). Stop.
   - **PR exists** → assess via the open-PR decision tree. Take the first matching action. Stop.
5. If every phase is merged → end condition (below).

### Open-PR decision tree

Apply top-down; act on the first match (verbatim from the dispatch skill, customised checks):

- **3a. Ready to merge.** All blocking checks `SUCCESS` (`ci`/`build`/`frontend-build` as applicable, `commitlint`, `branch-name`, `dco`, `gitleaks`, `dependency-review`, `regen-and-diff`, `openapi-lint`/`spectral`, `helm-lint`, `api-chart-lint`) AND `mergeable: MERGEABLE` AND `mergeStateStatus != BLOCKED` AND one of: latest review body starts with `LGTM` (case-insensitive) **OR** the only outstanding finding is the 400-line cap AND the PR body cites the §4 soft-target override **OR** the 3c-loop-terminator fired with an effectively-resolved verdict. → `gh pr merge <pr#> --squash` (no `--delete-branch`). Log `MERGED`.
- **3b. Auto-loop alive.** `claude-review` check `IN_PROGRESS`/`QUEUED`, or a `Claude Code Review` run on the branch within the last 15 min. → wait.
- **3c. Findings + no fixer activity.** Latest review starts with `Findings —`, no `claude-review` run active, no commit since the review timestamp. Run the **3c-loop-terminator** first (compare to prior cycle's first finding):
  - Repeated finding is the **400-line cap** → dispatch a body-edit fixer to cite the §4 soft-target override, then dispatch a manual reviewer for a fresh verdict.
  - Repeated finding is **anything else** → escalate (log `ACTION` + `CronDelete` self).
  - Not a repeat → dispatch a manual fixer (template below). Log `FIXER`.
- **3d. CI complete + no review yet.** All blocking checks have a conclusion, reviews empty, no `claude-review` run active. → dispatch a manual reviewer (template below). Log `REVIEWER`.
- **3e. CI still running.** → wait.

**Informational checks (NEVER block merge):** `claude-review` itself, `CodeQL`/`Analyze (java-kotlin)`, `deploy` (Cloudflare Pages preview).

### Fix-cycle budget

3 fix passes per phase. After 3, escalate: log `**ACTION:** phase <P> exhausted fix budget; human intervention required` + `CronDelete` self.

## Implementer agent prompt template

Dispatch with `Agent({ description, subagent_type: "general-purpose", isolation: "worktree", run_in_background: true, prompt })`. Fill the BRACKETS per phase. **Before dispatching, run `scripts/adr-context.sh <every path the phase touches>` and paste its stdout into the MANDATORY READING block.**

```
You are an implementation agent. **Phase [P] of the survey rating-undo rollout**: [one-paragraph goal from the phase map].

## Background
Plan: `docs/superpowers/plans/2026-05-30-survey-rating-undo.md` — read **[Phase section]** in full and follow its steps exactly (it is TDD, bite-sized, with complete code). Spec: `docs/superpowers/specs/2026-05-30-survey-undo-design.md`. The survey bounded context is ADR-0056; campaign lifecycle/lock is ADR-0059.

## MANDATORY READING — read these ADRs in full before writing any code. They are binding rules for the paths this phase touches.
[paste `scripts/adr-context.sh <paths>` stdout verbatim. If empty: "No path-bound ADRs apply beyond ADR-0056/0059. Proceed."]

## Domain skill
Before you begin, invoke `/[jvm-backend | frontend | schemas]` for the conventions and gotchas specific to this work.

## Your scope
Implement **only [Phase P]** of the plan, task by task. [List the plan's task numbers for this phase.] Do NOT implement other phases. Do NOT touch files outside this phase's declared scope.

## Comment style
Comments document non-obvious WHY, in one line. Default to no comment. No multi-paragraph/multi-line comment blocks in new code (forbidden — CLAUDE.md). No PR/task/caller references in comments. Collapse any multi-paragraph source docstrings to one line BEFORE the first push — the §6a auto-reviewer flags this every cycle.

## How to ship
1. Branch off `origin/main` as `[branch from phase map]`.
2. Implement following the plan's steps (write the failing test first where the plan says so).
3. Validate locally:
   - JVM: `./gradlew :survey:<module>:check :survey:<module>:spotlessCheck` (and `:survey:api:build` for wiring phases).
   - Frontend: `cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm build` (+ `pnpm api:check` if you touched a spec).
4. Commit with `git commit -s` (DCO) and Conventional Commit messages — single scope, no commas, allowlist `feat|fix|chore|refactor|test|docs`. Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>.
5. Push `git push -u origin [branch]`.
6. Open a PR via `gh pr create --base main` (owner `Ishou`, repo `bliss`). Title = `[PR title prefix] <subject>`. Body = Summary / Context (bounded context + layer, spec + ADRs, "Phase [P] of survey rating-undo rollout") / Test plan.
   [For Phase 2c ONLY: the PR body MUST cite the ADR-0001 §4 2026-05-25 soft-target override from the first push — this phase is pre-flagged cap-heavy.]

## Constraints
- ADR-0001 §4 400-line soft target (generated code excluded). Ask "should this split?" first; cite the override only when warranted.
- No cross-context imports. No new runtime deps without an ADR.
- DCO sign-off, conventional commits, no emojis.
- [Phase 0.5 only: docs-only; do NOT touch any code or schema files.]
- [Phase 1 only: schema-first — this is the schema PR; no implementation.]
- [Phase 2a only: NO behavior change; do not alter repo constructors — only swap the connection-acquisition line.]
- [Phase 2c only: ADR-0059 amendment is already on `main` (Phase 0.5); do not re-amend it here.]

## CI auto-fix loop
After pushing, monitor CI and auto-fix until green.
1. Wait ~60 s, then poll `gh pr checks <pr#>` (or `gh api`) every ~30 s until every BLOCKING check terminates. Blocking: build/ci, commitlint, branch-name, dco, gitleaks, dependency-review, regen-and-diff, openapi-lint/spectral, helm-lint, api-chart-lint. Informational (do NOT block/fix): claude-review, CodeQL/Analyze, deploy.
2. If a blocking check FAILED, diagnose + fix: dco → `git commit -s --amend --no-edit; git push --force-with-lease`; commitlint → amend single conventional scope; build (gradle) → reproduce with `./gradlew :survey:<m>:check`; build (frontend) → `pnpm typecheck && lint && test && build`; regen-and-diff → `cd frontend && pnpm api:generate`, commit; openapi-lint → fix schema lint.
3. Budget: 3 fix passes max, then STOP and report the blocker.
4. claude-review findings: same 3-pass budget.
5. Only report once all blocking checks are green (or budget exhausted).

## Report back (under 250 words)
Branch + PR number + URL. File inventory + LOC (main vs tests). Test/lint/build outputs. Decisions beyond the brief. Blockers.
```

## Manual reviewer dispatch prompt

```
Agent({ description: "Manual reviewer for PR #<N> (survey-undo, auto-reviewer hung)", subagent_type: "general-purpose", isolation: "worktree", run_in_background: true, prompt: `
You are a code reviewer (NOT the implementer — ADR-0001 §6a). Review PR #<N> in Ishou/bliss, the survey rating-undo rollout Phase <P>.

Before you begin, invoke `/[jvm-backend | frontend | schemas]` for the conventions.

1. Fetch the diff: `gh pr diff <N>`. Read the plan's Phase <P> section and the spec to confirm the PR implements what was specified.
2. Check: correctness vs the plan; TDD (tests present + meaningful); no cross-context imports; ADR-0056/0059 adherence; comment-style (no multi-line blocks); the no-oracle 404/410 undo semantics (Phase 2c); schema-first additive-only (Phase 1).
3. Post the verdict with `gh pr review <N>`:
   - Clean → first line exactly `LGTM, no findings.` (use `--approve`; if the same-actor token rejects approve, use `--comment` — the merge gate matches on the `LGTM` first line either way).
   - Issues → first line `Findings —` then a numbered list, each citing rule + location + recommended fix. Use `--request-changes` (or `--comment` on token rejection).
Do NOT merge. Report the verdict back in one line.
` })
```

## Manual fixer dispatch prompt

```
Agent({ description: "Manual fixer for PR #<N> (survey-undo, findings open)", subagent_type: "general-purpose", isolation: "worktree", run_in_background: true, prompt: `
You are a fixer agent for PR #<N> in Ishou/bliss (survey rating-undo Phase <P>). The §6a reviewer posted findings; the auto-fixer did not act.

Before you begin, invoke `/[jvm-backend | frontend | schemas]`.

1. Read the open findings: `gh pr view <N> --comments` and `gh pr diff <N>`.
2. Address EACH finding with the smallest correct change. Follow the plan's Phase <P> steps. Comment-style: one-line WHY only, no multi-line blocks.
3. Validate locally (gradle `:survey:<m>:check`/`spotlessCheck` or frontend `pnpm typecheck && lint && test && build`).
4. Commit `git commit -s` (conventional, single scope) + push.
5. Reply on the PR mapping each finding → commit SHA (as yourself, the fixer — never as the maintainer).
Budget: 3 fix passes. After 3, stop and report the unresolved findings. Report back in one line per finding (resolved/unresolved + SHA).
` })
```

## Logging format

Append chronological entries to the orchestration log's Event log. One line each:

```
- 2026-05-30T14:32Z · Phase 1 · DISPATCHED implementer (branch feat/survey-undo-schema)
- 2026-05-30T14:48Z · Phase 1 · PR #NNN opened; CI running
- 2026-05-30T15:02Z · Phase 1 · REVIEWER dispatched (CI green, no review)
- 2026-05-30T15:10Z · Phase 1 · MERGED (LGTM + green)
```

Reserve `**ACTION:** ...` lines for things the maintainer must do (escalations, fix-budget exhaustion, rollout-complete reminders).

## End condition

When Phase 3 merges:
- Append `**ACTION:** survey rating-undo rollout complete. Reminder: restore the stashed campaign-lock-log wip (`git stash list`), and the cron can be deleted.`
- `CronDelete <cron-id>`.
- Exit.
