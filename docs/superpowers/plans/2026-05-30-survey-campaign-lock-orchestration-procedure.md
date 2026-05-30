# Survey Campaign Lock — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the survey-campaign-lock multi-PR rollout.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days; recreate if rollout exceeds 7 days).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**State source of truth:** `docs/superpowers/plans/2026-05-30-survey-campaign-lock-orchestration-log.md`.

**Spec / Plan / ADR:**
- Spec: `docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md`
- Plan: `docs/superpowers/plans/2026-05-30-survey-campaign-lock.md`
- ADR: `docs/adr/0059-survey-campaign-lock.md`

---

## Standing maintainer authorization (recorded 2026-05-30)

From the in-session grant during the brainstorming + plan-writing flow:

1. **Subagent-driven execution, autonomous.** The maintainer asked for `/orchestrate` after picking option 1 (Subagent-driven execution) for the plan. The orchestrator dispatches implementer / reviewer / fixer agents and merges PRs on green CI + LGTM without per-step confirmation.

2. **400-line cap is a soft target (per ADR-0001 §4 2026-05-25 amendment).** The orchestrator MAY invoke the override proactively without escalating to the maintainer when a PR genuinely warrants it (e.g., a coherent layer that splitting would fragment). Invocation: the implementer prompt instructs pre-citing the override in the PR body from the first push so the §6a reviewer does not loop on it. Cap-only findings on PRs already citing the override → trigger 3c-loop-terminator (body-edit fixer + fresh reviewer).

3. **Auto-merge on green CI + LGTM.** Standing per memory: after a PR opens, the cron polls; once all blocking checks are `SUCCESS` and the latest review starts with `LGTM`, the cron runs `gh pr merge <pr#> --squash` (no `--delete-branch` to avoid worktree collision).

4. **No maintainer impersonation in comments.** Per memory: the orchestrator never posts `@<maintainer>`-authored content. All orchestrator comments are signed as the orchestrator and cite this section for §6a authorization.

5. **Bootstrap PR (this one) merges on §6a LGTM + green CI like any other.** Per memory ("Bootstrap PR uses standard 3a") — no special Wave-0 gate.

---

## Phase map

| Phase | Branch                              | Base                                   | PR title prefix                                                              |
|-------|-------------------------------------|----------------------------------------|------------------------------------------------------------------------------|
| A     | `docs/survey-campaign-lock-spec`    | `main`                                 | `docs(survey): campaign-lock spec, plan, ADR-0059, orchestration` (bootstrap) |
| B     | `feat/survey-campaign-openapi`      | `main` (after A merges)                | `feat(survey-api): openapi for campaign lock + GET /v1/campaign/current`     |
| C     | `feat/survey-campaign-backend`      | `main` (after B merges)                | `feat(survey): campaign lifecycle + 423 on rating POSTs`                     |
| D     | `feat/survey-campaign-frontend`     | `main` (after C merges)                | `feat(survey-frontend): campaign status + lock banner on /sondage`           |
| E     | `chore/survey-campaign-backfill`    | `main` (after C merges; parallel w/ D) | `chore(survey-scripts): one-shot campaign backfill from Modal logs`          |

D and E are independent after C merges. The cron may dispatch both implementers in the same tick (one Agent call per phase, both in a single message).

A is already authored locally as the bootstrap PR — the cron's first tick assesses it; it does NOT dispatch an implementer for A.

---

## Tick procedure

Apply on every cron fire. Take at most one action per tick.

### 0. Setup

```bash
cd "$(git rev-parse --show-toplevel)"
git fetch origin --quiet
```

If the procedure file is not yet on `origin/main` (bootstrap PR not merged), read it from `origin/docs/survey-campaign-lock-spec`:

```bash
git show origin/docs/survey-campaign-lock-spec:docs/superpowers/plans/2026-05-30-survey-campaign-lock-orchestration-procedure.md
```

### 1. Walk the phase map

For each phase A → E **in order**:

1. Resolve the phase's PR by branch name via `gh pr list --head <branch> --state all --json number,state,mergeable,mergeStateStatus,headRefName --limit 1`.
2. Classify:
   - **MERGED** → continue to the next phase.
   - **CLOSED-not-merged** → escalate (§5).
   - **OPEN** → assess via the open-PR decision tree (§3) and take the matched action. Stop the walk; tick ends.
   - **No PR + previous phase MERGED** → dispatch implementer (§4) for this phase. Stop the walk; tick ends.
   - **No PR + previous phase NOT merged** → wait. Stop the walk; tick ends.

Special-case for D and E: both depend on C only. If C is merged and neither D nor E has a PR, dispatch both implementers in the same tick (one message, two Agent calls). After dispatch, walk continues — but since both are now "dispatched but no PR yet", the next tick will pick up.

### 2. Refresh remote state (cheap, idempotent)

The first ~5 minutes after dispatch, the implementer is still in its worktree; no PR will exist. Don't dispatch a duplicate — the orchestrator tracks dispatched-but-not-yet-opened phases in the log file (§6).

### 3. Open-PR decision tree

Apply top-down; act on the first match.

#### 3a. Ready to merge

ALL of the following:

- Every BLOCKING check is `SUCCESS`. Blocking checks for this rollout:
  - Bootstrap A: `commitlint`, `branch-name`, `dco`, `gitleaks`, `registry-coherence`, `dependency-review`.
  - Schema B: A's set + `openapi-lint`.
  - Backend C: A's set + `ci` (Gradle build/test/spotless/Konsist), `openapi-typescript-drift` (frontend regen not yet landed; B handles the consumer side via `pnpm api:check` driven by the upcoming D — this check will show a diff but is informational at this stage and SHOULD pass once D regenerates types in Phase D).
  - Frontend D: A's set + `ci` (frontend-build, typecheck, lint, vitest), `openapi-typescript-drift` (must show no drift now that D regenerates).
  - Backfill E: A's set + script's local `pytest`.
- `mergeable: MERGEABLE` AND `mergeStateStatus != BLOCKED`.
- One of:
  - The most recent review body starts with `LGTM` (case-insensitive).
  - The only outstanding findings are about the 400-line target AND the PR body already cites the §4 soft-target override (per §3c-loop-terminator).

**Action:**
```bash
gh pr merge <pr#> --squash
```
(No `--delete-branch` — see dispatch skill's "common failure modes" for the worktree collision.)

Log entry:
```
- 2026-05-30T<HH:MM>Z PHASE-<X> PR #<pr#> MERGED ::squash
```

#### 3b. Auto-loop alive

`claude-review` check is `IN_PROGRESS` or `QUEUED`, OR a `Claude Code Review` workflow run on the branch is < 15 min old.

**Action:** wait. No action.

#### 3c. Findings + no fixer activity

Latest review body starts with `Findings —`, AND no `claude-review` workflow currently running on the branch, AND no commit on the branch since the review timestamp.

**3c-loop-terminator (BEFORE dispatching a manual fixer):**
Compare the latest review's findings to the **prior** §6a review's findings. If the first finding's rule-citation + claim are essentially identical (rule + location + recommended-fix shape are all the same) to the prior cycle's first finding AND the diff has materially changed between the two reviews:

- **If the repeated finding is the 400-line target:** dispatch a body-edit fixer that updates the PR body to cite the §4 2026-05-25 soft-target amendment and this procedure's "Standing maintainer authorization" §2, then dispatch a manual reviewer for a fresh verdict.
- **If the repeated finding is anything else:** escalate per §5. Append `**ACTION:** identical finding on cycles N-1 and N for PR <pr#>; auto-loop terminated, human intervention required` and `CronDelete` self.

If 3c-loop-terminator does NOT fire: dispatch a manual fixer (§4.2).

#### 3d. CI complete + no review yet

Every BLOCKING check has a `conclusion`, reviews list is empty, no `claude-review` workflow currently running on the branch.

**Action:** dispatch a manual reviewer (§4.3).

#### 3e. CI still running

Otherwise → wait. No action.

### 4. Agent dispatch

All Agents:
- `isolation: "worktree"` (mandatory for writes — required even for the reviewer; required for the body-edit fixer).
- `subagent_type: "general-purpose"`.
- `run_in_background: true` (the cron tick must return promptly).

#### 4.1 Implementer agent prompt template

Substitute `<PHASE_LETTER>`, `<BRANCH_NAME>`, `<PR_TITLE>`, `<PLAN_SECTION_RANGE>` (e.g. `§B1–B4` for schema), `<ADR_CONTEXT_PATHS>`, `<DOMAIN_SKILL>` per phase.

```
You are an implementation agent. Phase <PHASE_LETTER> of the survey campaign-lock multi-PR rollout: <one-paragraph goal>.

## Background

Plan: docs/superpowers/plans/2026-05-30-survey-campaign-lock.md — read <PLAN_SECTION_RANGE> in full.
Spec: docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md — read sections referenced by the plan.
ADR: docs/adr/0059-survey-campaign-lock.md — this is the architectural decision you implement.

Both the spec and ADR are on origin/main once the bootstrap PR (Phase A) merges. If you are dispatched before that, read them from origin/docs/survey-campaign-lock-spec.

## MANDATORY READING — read these ADRs in full before writing any code. They are not background context; they are binding rules for the paths this PR touches.

<paste verbatim output of scripts/adr-context.sh <every path the PR will touch>>

If the helper emits no matching ADRs, replace this block with: `No path-bound ADRs apply to this PR. Proceed.`

## Domain skill

Before you begin, invoke `<DOMAIN_SKILL>` for the conventions and gotchas specific to this work.
- Phase B: invoke `/schemas`.
- Phase C: invoke `/jvm-backend`.
- Phase D: invoke `/frontend`.
- Phase E: no domain skill; follow the plan strictly.

## Your scope

Execute the plan tasks <PLAN_SECTION_RANGE> verbatim — every step, every commit. The plan is bite-sized TDD; follow each step's "write the failing test → run → implement → run → commit" sequence.

DO NOT:
- Touch files outside the plan's "Files" section for this phase.
- Refactor code unrelated to the phase's scope.
- Add dependencies not already used in the relevant module.
- Submit a PR before the entire plan section is implemented and all checks listed pass locally.

## How to ship

1. Branch off `origin/main` as `<BRANCH_NAME>` (only after the previous phase's PR has merged — verify via `gh pr view <prev-pr> --json state`).
2. Implement per the plan; commit per its checkbox structure with DCO sign-off (`git commit -s`).
3. Validate locally per the plan's commands (`./gradlew ...`, `pnpm ...`, `pytest ...`).
4. Push: `git push -u origin <BRANCH_NAME>`.
5. Open a PR via `gh pr create --base main --title "<PR_TITLE>" --body "..."`.

PR body skeleton:
```
## Summary
- <bullet list per plan section>

## Test plan
- [ ] <commands from the plan>

## Cap-override note (if applicable)
This PR exceeds the 400-line target; cited per ADR-0001 §4 (2026-05-25 soft-target amendment) and the survey campaign-lock orchestration procedure's "Standing maintainer authorization" §2. The scope is a coherent layer that splitting would fragment.

🤖 Generated with Claude Code
```

Pre-cite the cap override in the PR body from the first push if the phase is cap-heavy. Cap-heavy phases for this rollout:
- Phase C (backend): expected ~700–1100 LOC across migration + domain + application + infrastructure + api + tests.
- Phase D (frontend): expected ~500–700 LOC across hook + banner + disabled props + route wiring + tests.

## Constraints

- ADR-0001 §4: 400-line target (soft per 2026-05-25). Excludes generated code (TS types regenerated from openapi.yaml are excluded).
- Conventional commits: types `feat | fix | chore | refactor | test | docs` only. Single hyphenated scope. NO `perf:`, `style:`, `wip:`.
- DCO sign-off on every commit.
- No emojis.
- No cross-context imports (CLAUDE.md, ADR-0001 §1).
- Survey context boundaries: `domain → application → infrastructure / api`. Konsist enforces.
- French copy stays French. Don't translate.

## Comment style

Comments document non-obvious WHY, in one line. Default to no comment. If you write one, it's a single line on a non-obvious *why* — a hidden constraint, a subtle invariant, a workaround for a specific bug. Don't explain WHAT the code does (well-named identifiers do that). Don't reference PRs / tasks / callers / the current fix — those rot. Multi-paragraph comment blocks (consecutive `//` / `#`, multi-line `/* */` or `"""`) are forbidden in new code: if you need more than one line, you've found ADR-worthy context — write the ADR and link from one line. For verbatim Python ports from external sources, collapse multi-paragraph source docstrings to one-liners BEFORE the first push. The auto-§6a reviewer flags this and the auto-fixer will cycle 2–4 times collapsing them otherwise — pre-empt.

## CI auto-fix loop

After pushing the PR, monitor CI and auto-fix until green.

1. Wait ~60 s, then poll `gh pr checks <pr#>` every ~30 s until every BLOCKING check has terminated.

   Blocking checks (must be `success` before reporting back):
     ci (Gradle build/test/spotless/Konsist for Phase C),
     frontend-build (Phase D),
     commitlint, branch-name, dco, gitleaks,
     dependency-review, registry-coherence,
     openapi-lint (Phase B), openapi-typescript-drift (Phase D),
     spectral (if installed for survey openapi)

   Informational (do NOT block on, do NOT auto-fix):
     claude-review, Analyze (java-kotlin) / CodeQL

2. If any blocking check FAILED, diagnose + fix. Common patterns:

   - dco              → git commit -s --amend --no-edit; force-push
   - commitlint       → amend with single conventional scope (no commas)
   - ci (gradle)      → ./gradlew :<scope>:check locally; fix; push
   - frontend-build   → cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm build
   - openapi-typescript-drift → pnpm api:check; commit the regen diff
   - openapi-lint / spectral → fix lint findings; re-run locally

3. Budget: 3 fix passes max. After 3, STOP and report the blocker. Do NOT grind on a flaky check or wedged toolchain.

4. claude-review findings: address with the same 3-pass budget. If the bot still reports findings after pass 3, stop and report the open list.

5. Only report back once all blocking checks are green (or budget exhausted).

## Report back (under 250 words)

- Branch + PR number + URL.
- File inventory + total LOC (main vs tests; cite the cap-override invocation if used).
- Test/lint/build outputs (last lines + final status).
- Any decisions beyond the brief.
- Any blockers.
```

#### 4.2 Manual fixer dispatch prompt

```
You are a fixer agent dispatched by the orchestrator. PR #<pr#> on branch <branch>: the §6a reviewer posted findings; the auto-fixer has not posted a fix commit within 15 minutes. Take over.

## Background

Plan: docs/superpowers/plans/2026-05-30-survey-campaign-lock.md.
Procedure: docs/superpowers/plans/2026-05-30-survey-campaign-lock-orchestration-procedure.md (read §3c-loop-terminator if the findings are about the 400-line cap).
The standing maintainer authorization (Procedure §2) grants you the cap override; cite it in the PR body if the open finding is cap-only.

## What to do

1. `gh pr checkout <pr#>` in your worktree.
2. `gh pr view <pr#> --json reviews,body` — read the latest review body in full.
3. For each open finding:
   - Make the targeted code change.
   - Commit per the plan's conventional-commit style (`-s` DCO trailer).
4. Push.
5. Reply on the review thread (or top-level if no thread IDs) mapping each finding → commit SHA.
6. Monitor CI; auto-fix per the CI loop.
7. Report back: list of findings + fix commits + final CI state.

DO NOT change the PR title.
DO NOT impersonate the maintainer in any comment.
Cap: 3 fix passes. After 3, stop and surface the open findings.
```

#### 4.3 Manual reviewer dispatch prompt

```
You are a reviewer agent dispatched by the orchestrator. PR #<pr#> on branch <branch>: CI is green; the auto-§6a reviewer has not posted a verdict within 15 minutes. Take over.

## Background

Plan: docs/superpowers/plans/2026-05-30-survey-campaign-lock.md.
Spec: docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md.
ADR-0059: docs/adr/0059-survey-campaign-lock.md.
Procedure: docs/superpowers/plans/2026-05-30-survey-campaign-lock-orchestration-procedure.md.

Standing maintainer authorization (Procedure §2): the cap override may be invoked by the PR body; treat that as resolved on cap findings.

## What to do

Invoke the `reviewer` skill. It encodes ADR-0001 §6a's in-scope / out-of-scope rules and the LGTM-or-Findings output format.

1. `gh pr diff <pr#>` — read the full diff.
2. Walk §6a's checklist: implementer-≠-reviewer holds (you are not the implementer); ADR violations; CLAUDE.md violations; missing tests; cap finding (if applicable, check whether the PR body cites the override → if yes, treat as resolved).
3. Post a single review via `gh pr review <pr#> --comment --body "..."`:
   - Start with `LGTM, no findings.` if clean.
   - Start with `Findings —` and list one finding per paragraph if not.
4. Format per finding: rule citation + file/line + proposed fix.

DO NOT use `--approve` — same-actor-token restriction on cron-launched sessions rejects it; `--comment` is fine, the merge gate matches on `LGTM`-prefix.
DO NOT impersonate the maintainer.

Report back: review URL + verdict (LGTM | Findings) + finding count.
```

### 5. Escalation

When 3c-loop-terminator fires on a non-cap finding, OR an implementer / fixer reports unrecoverable failure, OR a phase's PR is CLOSED-not-merged:

1. Append to the log file (§6):
   ```
   **ACTION:** <description>. PR #<pr#>. Reason: <one line>.
   ```
2. `CronDelete <cron-id>` to stop future ticks.
3. The maintainer will re-engage via the log file.

### 6. Logging format

Every tick that takes an action appends one line (or a small block for actions with subentries) to `2026-05-30-survey-campaign-lock-orchestration-log.md`. Format:

```
- 2026-05-30T<HH:MM>Z <event>
```

Events:

- `PHASE-<X> DISPATCHED implementer Agent` — just dispatched.
- `PHASE-<X> PR #<pr#> OPENED` — implementer reported back; PR URL recorded.
- `PHASE-<X> PR #<pr#> CI green; dispatched manual reviewer` — §3d action.
- `PHASE-<X> PR #<pr#> reviewer LGTM` — review posted; ready to merge on next tick (unless CI still pending).
- `PHASE-<X> PR #<pr#> Findings; dispatched manual fixer` — §3c action.
- `PHASE-<X> PR #<pr#> body-edit fixer dispatched (3c-loop-terminator on cap finding)` — §3c special case.
- `PHASE-<X> PR #<pr#> MERGED ::squash` — merge action taken.
- `**ACTION:** <description>` — escalation; cron self-deletes.

Wait-ticks (3b, 3e) do NOT log. The log is for state transitions, not heartbeats.

### 7. End condition

When Phase D and Phase E (the two terminal phases) are both MERGED:

1. Append:
   ```
   - 2026-05-30T<HH:MM>Z ROLLOUT-COMPLETE
   **ACTION:** rollout complete. Recover: stash list, watchdog stop (if running), final review of `scripts/survey/backfill_campaigns.py` dry-run output before prod backfill.
   ```
2. `CronDelete <cron-id>`.
3. Exit.

---

## Tick output style

Concise. One line per phase examined. Plus the single action taken (if any). Don't restate the procedure back; the maintainer can re-read the file directly.

Example healthy tick:

```
A: MERGED.
B: PR #<n> OPEN, ci pending → wait.
C–E: no PR; B not merged → wait.
No action this tick.
```

Example action tick:

```
A: MERGED.
B: PR #<n> OPEN, all checks green, LGTM → merge.
Action: gh pr merge <n> --squash → ok.
```

Tick output is for the orchestrator log only; the maintainer reads the log file when convenient.
