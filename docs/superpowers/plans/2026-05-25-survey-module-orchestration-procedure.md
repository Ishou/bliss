# Survey Module — Orchestration Procedure (cron-driven)

This file is the self-contained procedure the cron-fired orchestrator follows on every tick. The cron prompt is just "follow this file"; all the logic lives here so it can be inspected, reviewed, and amended via PR rather than buried in an opaque cron `prompt` field.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days per `CronCreate` contract — recreate if rollout exceeds 7 days).

**CWD:** run from the repo root. Use `cd "$(git rev-parse --show-toplevel)"` if the shell is not already there.

**State source of truth:** `docs/superpowers/plans/2026-05-25-survey-module-orchestration-log.md` (read from `origin/main` once the orchestration PR has merged; before then, read from the spec/orchestration branch).

## Source documents

- **Spec:** `docs/superpowers/specs/2026-05-25-survey-module-design.md` (lands on `main` with this PR, branch `docs/survey-module-orchestration`).
- **Plan:** `docs/superpowers/plans/2026-05-25-survey-module.md` (lands on `main` with this PR, branch `docs/survey-module-orchestration`).
- **ADR-0056** (companion to this rollout, lands as Phase 1).

## Standing maintainer authorization (recorded 2026-05-25)

For this rollout, @Ishou granted the following standing rules in-session. Verbatim grants — cite-able by §6a reviewer:

> **400-line cap:** "for the 400 line-cap: i grant you explicit authorization to by-pass it if you deem it necessary, the 400 line-cap should trigger a question about 'should the PR be split?' but it does not mean that it should always be the case"

> **Cadence + autonomy + reviewer-hang fallback** (from prior rollouts, still in force — "2-minute polling, durable cron, `claude-review` IN_PROGRESS = wait"; reviewer/fixer hang → dispatch manual after 15 min)

> **Autonomy:** Ishou delegates orchestration decisions; decide and log, don't ask unless blocking.

**Operational consequences:**

- PR4 (`feat/survey-application`) and PR7 (`feat/survey-worker-and-charts`) are pre-flagged in the plan as cap-override candidates. The implementer agents invoke the override in the PR body citing this standing-authorization section.
- §6a reviewer treats the standing authorization as a satisfied "maintainer ack" for the cap-override gate when the PR body cites it explicitly.
- The orchestrator does NOT post `@Ishou`-authored comments to confirm — that's impersonation (standing rule from prior rollouts). The standing grant is the cite-able artefact.

## Phase map

Strictly sequential — every phase depends on the previous one being merged on `main`. No stacked PRs in this rollout.

| Phase | Branch | Base | PR title prefix |
|---|---|---|---|
| 1 | `feat/survey-adr` | `main` | `feat(survey-docs): ADR-0056 — survey bounded context` |
| 2 | `feat/survey-openapi` | `main` (after Phase 1 merges) | `feat(survey-api): OpenAPI 3.1 schema-only contract` |
| 3 | `feat/survey-domain` | `main` (after Phase 2 merges) | `feat(survey-domain): domain layer — types, invariants, sampler, calibration` |
| 4 | `feat/survey-application` | `main` (after Phase 3 merges) | `feat(survey-application): use cases, filters 1-7 port, §8.1 codec` |
| 5 | `feat/survey-infrastructure` | `main` (after Phase 4 merges) | `feat(survey-infrastructure): Postgres adapters, identity client, NATS consumer` |
| 6 | `feat/survey-api` | `main` (after Phase 5 merges) | `feat(survey-api): Ktor HTTP edge with auth-optional middleware` |
| 7 | `feat/survey-worker-and-charts` | `main` (after Phase 6 merges) | `feat(survey-worker): worker subcommands + Helm chart + db-chart + bootstrap` |
| 8 | `feat/survey-frontend` | `main` (after Phase 7 merges) | `feat(survey-frontend): /sondage route + /compte integration` |
| 9 | `feat/survey-nats-and-launch` | `main` (after Phase 8 merges) | `feat(survey): NATS consumer live + launch (maintainer first)` |

Each phase's tasks live in `docs/superpowers/plans/2026-05-25-survey-module.md` under the matching `## PR<N>` section. The implementer agent reads its phase's section and executes the listed tasks in order.

## Tick procedure

### 1. Refresh local view of remote state

```sh
cd "$(git rev-parse --show-toplevel)"
git fetch origin --quiet
```

### 2. Determine current phase

Walk the phase map in order. For each phase, look up its PR:

```sh
gh pr list --state all --head <branch> --json number,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,reviews -L 1
```

- `state: "MERGED"` → phase done; move to next.
- `state: "OPEN"` → this is the active phase; proceed to step 3.
- `state: "CLOSED"` (not merged) → **escalation:** post to log "**ACTION:** Phase X PR was closed without merging. Orchestration paused." `CronDelete` self. Exit.
- No PR found → if previous phase is `MERGED` (or this is Phase 1 and there's nothing to wait on), dispatch the implementer (step 4). Else, no action this tick (waiting on previous phase).

### 3. Open PR — assess and act

Inputs needed (one `gh pr view` call):

```sh
gh pr view <pr#> --json number,state,reviewDecision,mergeStateStatus,mergeable,reviews,statusCheckRollup
gh pr checks <pr#> --json name,status,conclusion
```

Decision tree (evaluated top-down; act on the first matching branch):

#### 3a. Ready to merge

Merge when ALL of:
- ALL blocking checks have `conclusion: "success"`. Blocking set: `ci`, `frontend-build` (if frontend touched), `commitlint`, `branch-name`, `dco`, `gitleaks` / `secret-scan`, `dependency-review`, `regen-and-diff` (if frontend types touched), `spectral` / `openapi-lint` (if openapi touched), `helm-lint` / `api-chart-lint` (if chart touched), `survey-export-csv-byteequal` (Phase 7+). **Informational checks NEVER block merge:** `claude-review` itself, `codeql` / `analyze-java-kotlin`, `deploy` (Cloudflare Pages preview).
- `mergeable: "MERGEABLE"` AND `mergeStateStatus != "BLOCKED"`.
- ONE of:
  - `reviewDecision == "APPROVED"` OR most recent review body's first line is `LGTM` (case-insensitive); OR
  - The only outstanding bot findings are about the 400-line cap-override AND the PR body cites either the docs-bundle category OR this file's "Standing maintainer authorization" section.

Detection heuristic for "only the cap-override finding remains": fetch the latest review body, scan for headings matching `^## Finding`. If every finding's title contains `400-line cap`, `cap-override`, `maintainer sign-off`, or `@Ishou`, treat the review as effectively-resolved.

Then:

```sh
gh pr merge <pr#> --squash
```

(Drop `--delete-branch` — it triggers a local prune touching `main` which collides with agent worktrees. Branch can be cleaned later via GitHub UI.)

Log: `<timestamp> · phase <name> · merged via squash (sha <sha>) [reviewer state: <APPROVED | cap-override-only>]`. Move to next phase next tick.

#### 3b. Auto-fix loop is alive — wait

If the `claude-review` check (or any other automated workflow) is `status: "in_progress"` or `status: "queued"`:

Log: `<timestamp> · phase <name> · claude-review in_progress · no action`. Exit tick.

#### 3c. Findings posted, fixer hasn't acted — dispatch manual fixer

If `reviewDecision == "CHANGES_REQUESTED"` OR the latest review body's first line contains "Findings" (case-insensitive), AND `claude-review` is NOT currently running, AND no commit has landed on the branch since the last review event:

→ Dispatch a manual fixer (template below). Increment the phase's fix-cycle counter in the log. If counter > 3: escalate (step 5).

#### 3d. CI complete, no review yet — dispatch manual reviewer

If ALL blocking checks have a `conclusion` (not `null`), AND there are zero reviews (`reviews` array empty), AND `claude-review` is NOT currently running:

→ Dispatch a manual reviewer (template below). Log the dispatch.

#### 3e. CI still running — wait

Otherwise (some blocking check is `null`/`in_progress`/`queued`): no action this tick. Log: `<timestamp> · phase <name> · CI in progress · no action`.

### 4. Dispatch implementer for next phase

Conditions: previous phase MERGED (or this is Phase 1), no PR exists for this phase yet.

Use the Agent tool:

```
Agent({
  description: "Phase <N> · <branch>",
  subagent_type: "general-purpose",
  isolation: "worktree",
  prompt: <see "Implementer agent prompt" below, with bracketed values filled>
})
```

Log the dispatch: `<timestamp> · phase <N> dispatched · branch <new-branch>`.

Don't dispatch more than one phase per tick. The tick ends after one dispatch.

### 5. Escalation (fix-cycle budget exceeded, OR PR closed-without-merge, OR unknown state)

- Append to log: `**ACTION:** <reason>. Orchestration paused.`
- `CronDelete <this-cron-id>` to stop future ticks.
- Exit tick. User intervention required.

### 6. End condition — Phase 9 merged

If Phase 9's PR is MERGED:

- Append to log:
  ```
  **ACTION:** Survey-module rollout complete. Remind user to:
   (a) flip the feature flag for the maintainer-only allowlist (set SURVEY_FLAG_USER_IDS in bliss-survey-api-env, redeploy).
   (b) onboard the existing Sheets cohort per spec §12.
   (c) sunset the Sheets workflow (docs PR in bliss-clue-ai marking scripts/campaign/ superseded).
   (d) close this cron: CronDelete <id> (if not already self-deleted).
  ```
- `CronDelete <this-cron-id>`.
- Exit.

## Implementer agent prompt (template — fill BRACKETS)

```
You are the implementer for **Phase [N]** of the Bliss survey-module rollout.

## Source documents (read these first)
- Plan: `docs/superpowers/plans/2026-05-25-survey-module.md` — find the "## PR[N]" section. Each Task in it has exact files + steps + code blocks.
- Spec (background only): `docs/superpowers/specs/2026-05-25-survey-module-design.md`.
- ADR-0056 (background only, if not Phase 1): `docs/adr/0056-survey-bounded-context.md` (already on main if you're past Phase 1).

## Before coding
Invoke whichever skills are relevant:
- For Kotlin work (Phases 1–7, 9): no specific skill needed; read CLAUDE.md and ADR-0001/0003/0044/0049/0050 as already linked from the plan.
- For frontend work (Phase 8): invoke the `frontend` skill to load ADR-0002 §4 conventions, Panda CSS recipes, Vitest + Testing Library, eslint-plugin-boundaries layering.

## Your scope
1. Base your work branch off `origin/main` as `[NEW_BRANCH_NAME]`.
2. Execute every Task listed under "## PR[N]" in the plan, in order. Each Task's steps are bite-sized (~2-5 min each). Follow TDD strictly: write failing test → run → implement → run → commit.
3. Each commit follows Conventional Commits with single-context scope. Type allowlist (from `.commitlintrc.yml`): `feat | fix | chore | refactor | test | docs`. **No `perf`, `style`, `wip`, `build`, `ci`, `revert`** — commitlint rejects them. DCO sign-off mandatory (`git commit -s`). Subject's first word must be lowercase (commitlint subject-case).
4. After all tasks done, validate locally per the plan's "Final sweep" task — typically `./gradlew :survey:<layer>:check` and/or `./gradlew build --parallel`. For frontend: `pnpm typecheck && pnpm test && pnpm a11y && pnpm api:check`.
5. Push: `git push -u origin [NEW_BRANCH_NAME]`.
6. Open the PR via `gh pr create --base main --title "..." --body "..."` (the plan's task includes the exact title and body block). For PR4 and PR7, the body invokes the cap-override category citing the procedure file's "Standing maintainer authorization" section.

## CI auto-fix loop (after pushing the PR)
1. Wait ~60 s, then poll `gh pr checks <pr#>` every ~30 s until every BLOCKING check terminates.
   Blocking: `ci`, `frontend-build` (if frontend), `commitlint`, `branch-name`, `dco`, `gitleaks`/`secret-scan`, `dependency-review`, `regen-and-diff` (if OpenAPI types touched), `spectral`/`openapi-lint` (if OpenAPI touched), `helm-lint`, `api-chart-lint`, `survey-export-csv-byteequal` (Phase 7+).
   Informational (don't block): `codeql`/`analyze-java-kotlin`, `claude-review`.
2. If any blocking check FAILED: diagnose + push fix. Common patterns:
   - `dco`: `git commit -s --amend --no-edit && git push --force-with-lease`.
   - `commitlint`: amend with single-context scope (no commas), lowercase subject first word, body lines ≤ 100 chars, no disallowed types (`perf`, `style`, `wip`, `build`, `ci`, `revert` all rejected).
   - `ci` (Spotless): `./gradlew spotlessApply` then re-commit.
   - `regen-and-diff`: `cd frontend && pnpm api:generate` (or `pnpm api:check`), commit the diff.
3. Budget: **3 fix passes max**. After 3, STOP and report the blocker in your final message.
4. Don't auto-fix `claude-review` findings here — those are handled by the orchestrator via a separate fixer dispatch. Just report back once CI is green.

## Constraints (binding)
- ADR-0001 §4: 400-line cap on meaningful changes (generated code excluded). Phases 4 and 7 are pre-flagged in the plan as cap-override candidates — invoke the override in the PR body citing the procedure file's "Standing maintainer authorization" section.
- ADR-0044 / ADR-0045 / ADR-0049 / ADR-0050 / ADR-0056: relevant cross-cutting decisions; honoured per the plan's task instructions.
- MANIFESTO.md: TDD, no mocks of own code, OpenTelemetry from day 1, structured logging (no println / no console.log), accessibility AA on frontend.
- No emojis in code or commits.
- No `--no-verify`, no `--no-gpg-sign`, no `git push --force` to `main`.
- Single bounded-context scope per commit (e.g. `feat(survey-domain): ...`).
- Branch must match `branch-name.yml` (`<type>/<short-description>` with `type` in feat/fix/chore/refactor/test/docs).

## Report back (≤250 words)
- PR URL + branch + line count (main vs. tests).
- Last lines of test/lint/build output (exit code matters most).
- Any decisions beyond the plan.
- Any blockers or open CI findings after the 3-pass budget.
```

## Manual reviewer dispatch prompt

```
You are a manual code reviewer dispatched because the auto-`claude-review` workflow did not fire (or completed without posting a review) on PR #[N].

Invoke the `reviewer` skill at the start of work. Follow ADR-0001 §6a:
- First line of your review body is either `LGTM` (approve) or `Findings` (request-changes).
- Each finding cites a specific rule (CLAUDE.md / ADR / MANIFESTO), gives a file:line reference, and proposes a concrete fix.
- Scope: lines actually changed in the PR diff. Don't review unrelated code.

Post the review via:

  gh pr review [N] --approve --body "<body>"        # if LGTM
  gh pr review [N] --request-changes --body "<body>"  # if Findings

If the same-actor token rejects `--approve`, fall back to:

  gh pr review [N] --comment --body "<body>"   # body still starts with "LGTM"

The orchestrator's merge gate matches on the first line being `LGTM`, regardless of approve vs. comment.

Report back: review verdict + summary (≤150 words).
```

## Manual fixer dispatch prompt

```
You are a manual fixer dispatched because the auto-fixer didn't act on PR #[N] within the auto-loop window. The PR has open review findings.

1. Read findings: `gh pr view [N] --json reviews,comments`. Identify each open issue.
2. Check out the PR's branch:

   gh pr checkout [N]

   (Uses worktree isolation from the orchestrator — you're already in an isolated worktree.)
3. Address each finding. Run the appropriate build/test sweep after each fix:
   - Kotlin: `./gradlew :survey:<layer>:check` (or `build --parallel` for cross-module changes).
   - Frontend (Phase 8): `cd frontend && pnpm typecheck && pnpm test && pnpm a11y`.
4. Commit with Conventional Commits + DCO sign-off (`git commit -s`). One commit per logical fix.
5. Push: `git push origin <branch>`.
6. For each thread on the PR, post a reply mapping finding → commit SHA via:

   gh pr comment [N] --body "Addressed: <commit-sha> — <finding summary>"

CI auto-fix loop (paste-ready): same as in the implementer prompt. Budget: 3 fix passes.

Report back: list of commit SHAs + which findings each addresses + remaining open items (if any).
```

## Logging format

Each tick appends one line to `docs/superpowers/plans/2026-05-25-survey-module-orchestration-log.md` under the "## Event log" section:

```
- `<ISO-8601 timestamp>` · phase `<name>` · `<verdict>` · `<action>`
```

Verdicts: `merged`, `dispatched-implementer`, `dispatched-reviewer`, `dispatched-fixer`, `waiting-ci`, `waiting-review`, `waiting-previous-phase`, `escalated`, `complete`, `error`.

Escalations and end-conditions get a **bold ACTION:** entry.

## What this procedure does NOT do

- It does not modify `main` directly. Every change goes via a PR.
- It does not force-push to anyone else's branch.
- It does not bypass any CI gate.
- It does not auto-write fixes to the implementation plan or spec — those changes go through a normal PR.
- It does not retry indefinitely. After 3 fix cycles per phase or any closed-not-merged PR, it escalates and self-deletes.
- It does not post `@<maintainer>`-authored comments — impersonation is blocked by the auto-mode classifier. The orchestrator posts as itself and cites the in-repo standing-authorization section.
