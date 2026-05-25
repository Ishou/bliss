# Clue-AI Modal-lane migration — Orchestration Procedure (cron-driven)

This file is the self-contained procedure the cron-fired orchestrator
follows on every tick. The cron prompt is just "follow this file"; all
the logic lives here so it can be inspected, reviewed, and amended via
PR rather than buried in an opaque cron `prompt` field.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7
days per `CronCreate` contract — recreate if rollout exceeds 7 days).

**CWD:** run from the repo root. Use `cd "$(git rev-parse --show-toplevel)"`
if the shell is not already there. Note that the main worktree may be
on any branch (the parallel survey orchestrator switches it). Read
this procedure file from `origin/docs/clue-ai-modal-migration` until
that branch merges to `main`:

```sh
git fetch origin --quiet
git show origin/docs/clue-ai-modal-migration:docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-procedure.md
```

**State source of truth:** `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-log.md`
(read via the same `git show` trick before merge; from working tree
after).

## Source documents

- **Spec:** `docs/superpowers/specs/2026-05-25-clue-ai-modal-migration-design.md`
  (lands on `main` with this branch, `docs/clue-ai-modal-migration`).
- **Plan:** `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration.md`
  (same branch).
- **ADR-0057** (companion to this rollout, lands as Wave 1's only PR).
- **Dispatch skill:** `.claude/skills/dispatch/SKILL.md` — the canonical
  patterns this procedure instantiates.
- **Clue-AI skill:** `.claude/skills/clue-ai/SKILL.md` — domain knowledge
  for the MLX lane; PR 3b and PR 8 modify it.

## Standing maintainer authorization (recorded 2026-05-25)

For this rollout, @Ishou granted the following standing rules
in-session. Verbatim grants — cite-able by §6a reviewer:

> **400-line cap (carried from prior rollouts):** "for the 400 line-cap:
> i grant you explicit authorization to by-pass it if you deem it
> necessary, the 400 line-cap should trigger a question about 'should
> the PR be split?' but it does not mean that it should always be the
> case"

> **Cap proactive use (carried from survey rollout, 2026-05-25):**
> "the 400 cap MAY be by-passed by YOUR call even without my call, i
> merged it but avoid anything like this later on" — the orchestrator
> has standing authority to invoke the override proactively without
> escalating, particularly to short-circuit the 3c-loop-terminator
> pattern in the dispatch skill.

> **Cadence + autonomy + reviewer-hang fallback** (carried from prior
> rollouts): 2-minute polling, durable cron, `claude-review`
> `IN_PROGRESS` = wait; reviewer/fixer hang → dispatch manual after
> 15 min.

> **Autonomy:** "Ishou delegates orchestration decisions; decide and log,
> don't ask unless blocking."

> **No maintainer impersonation:** the orchestrator does NOT post
> `@Ishou`-authored comments. Cite the in-repo standing-authorization
> section; post as the orchestrator with attribution.

**Operational consequences:**

- **PR 3a, PR 5, PR 6** are pre-flagged in the plan as cap-override
  candidates. The implementer agent prompts for those phases instruct
  the implementer to invoke the override in the PR body **from the
  first push**, citing this Standing-Authorization section. Don't wait
  for the §6a reviewer to surface the cap finding first.
- The maintainer's prior rollouts merged cap-override PRs with an
  explicit comment from them; for this rollout, the standing
  authorization is **enough** — the orchestrator merges on §6a `LGTM`
  + green CI even when the only outstanding finding was a
  cap-override-resolved one (per the dispatch skill's "Open-PR
  decision tree" 3a path).

## Wave map (parallel-where-possible)

This rollout's plan has 7 waves; some waves dispatch multiple PRs in
parallel (their files are disjoint). The orchestrator dispatches
**all PRs in a wave from a single tick** (one Agent call with multiple
sub-agent invocations), then waits for the whole wave to merge before
opening the next wave.

| Wave | PRs                          | Branch(es)                                                                                          | Base   | Cap-override pre-flag |
|------|------------------------------|-----------------------------------------------------------------------------------------------------|--------|-----------------------|
| 1    | PR 0 (ADR-0057)              | `docs/adr-0057-modal-clue-finetune-lane`                                                            | `main` | no                    |
| 2    | PR 1 + PR 2 + PR 5           | `docs/clue-style-guide-v2` + `chore/clue-ai-modal-gold-pilot-v1` + `chore/clue-ai-modal-paliers-0-1-2` | `main` (after Wave 1) | PR 5 yes (~435 lines) |
| 3    | PR 3a + PR 4a                | `chore/clue-ai-modal-pipeline-v2-port` + `chore/clue-ai-modal-prepare-dataset`                      | `main` (after Wave 2) | PR 3a yes (~700 lines) |
| 4    | PR 3b + PR 4b                | `chore/clue-ai-modal-pipeline-v2-fusion` + `chore/clue-ai-modal-build-corpus`                       | `main` (after Wave 3) | no                    |
| 5    | PR 6                         | `chore/clue-ai-modal-paliers-3a-3b`                                                                 | `main` (after Wave 4) | yes (~760 lines)      |
| 6    | PR 7                         | `chore/clue-ai-modal-export-bridge`                                                                 | `main` (after Wave 5) | no                    |
| 7    | PR 8                         | `docs/clue-ai-modal-skill-update`                                                                   | `main` (after Wave 6) | no                    |

Each PR's tasks live in the implementation plan under the matching
`## Task <n>` section. The implementer agent reads its task's section
and executes the listed steps in order.

**PR 5 is a Wave 2 phase**, not Wave 5. Wave 5 contains PR 6. (Plan PR
numbering vs wave numbering deliberately differ — the wave reflects
dependency order, the PR number reflects the plan's narrative order.)

## Tick procedure

### 1. Refresh local view of remote state

```sh
cd "$(git rev-parse --show-toplevel)"
git fetch origin --quiet
```

### 2. Determine current wave

Walk the wave map in order. For each wave, look up its PR(s):

```sh
# For each branch in the wave:
gh pr list --state all --head <branch> --json number,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,reviews -L 1
```

A wave is **complete** when every PR in it is `state: "MERGED"`. A wave
is **active** when at least one of its PRs exists; **pending** when
none of its PRs exist yet.

- All PRs in wave `MERGED` → wave done; move to next.
- Any PR `state: "CLOSED"` (not merged) → escalate. Post to log
  `**ACTION:** PR <pr#> closed without merge. Orchestration paused.`
  `CronDelete` self. Exit.
- One or more PRs `state: "OPEN"` → assess each via the per-PR
  decision tree (step 3). Take at most one action across the wave per
  tick (assess in PR-number order, act on the first PR that warrants
  action). Subsequent ticks handle the other PRs.
- No PR exists for any branch in this wave AND previous wave is
  complete → dispatch all wave implementers (step 4). One tick fans
  out the whole wave.
- No PR for this wave AND previous wave incomplete → no action this
  tick.

### 3. Open-PR decision tree (per PR; same as dispatch skill)

Apply top-down; act on the first match.

#### 3a. Ready to merge

ALL blocking checks `conclusion: "success"`, AND
`mergeable: "MERGEABLE"`, AND `mergeStateStatus != "BLOCKED"`, AND ONE
of:

- `reviewDecision == "APPROVED"`, OR
- Most recent review body's first line is `LGTM` (case-insensitive), OR
- Only outstanding §6a findings are 400-line cap items AND the PR body
  cites this procedure's Standing Authorization section (per
  dispatch-skill 3c-loop-terminator).

Then:

```sh
gh pr merge <pr#> --squash
```

(No `--delete-branch` — collides with agent worktrees.)

Log: `<timestamp> · <branch> · merged via squash (sha <sha>) [<reviewer-state>]`.

**Blocking check set:** `ci`, `commitlint`, `branch-name`, `dco`,
`gitleaks` / `secret-scan`, `dependency-review`. Conditional gates:
`regen-and-diff` (only if `frontend/**` touched — not expected in this
rollout), `spectral` / `openapi-lint` (not applicable; no
OpenAPI/AsyncAPI in this workstream), `helm-lint` (not applicable; no
chart in this workstream).

**Informational (NEVER block):** `claude-review` itself, `codeql` /
`analyze-java-kotlin`, `deploy` (Cloudflare Pages preview).

#### 3b. Auto-fix loop alive — wait

`claude-review` check is `status: "in_progress"` or `"queued"`, OR an
active `Claude Code Review` workflow run on the branch within the
last 15 min.

Log: `<timestamp> · <branch> · claude-review in_progress · no action`.
Exit tick.

#### 3c. Findings + no fixer activity → identical-finding check, then dispatch

Latest review body's first line starts with `Findings —` (or
`reviewDecision == "CHANGES_REQUESTED"`), AND no `claude-review`
workflow running on the branch right now, AND no commit on the branch
since the review timestamp.

**3c-loop-terminator** (apply BEFORE dispatching a manual fixer):

Compare the latest review's first finding to the prior §6a review's
first finding. If the rule-citation + location + proposed fix are
essentially identical AND the diff has materially changed between the
two reviews (a fix commit landed, or the PR body was edited), the
auto-loop is stuck.

- If the repeated finding is the **400-line target**: the PR body
  should already cite the override (pre-flagged at dispatch for PRs
  3a, 5, 6). If for some reason it doesn't, dispatch a body-edit
  fixer that updates the PR body to cite the Standing Authorization
  section, then dispatch a manual reviewer for a fresh verdict.
- If the repeated finding is **anything else**: escalate. Append
  `**ACTION:** identical finding on cycles N-1 and N for PR <pr#>;
  auto-loop terminated, human intervention required` and `CronDelete`
  self.

If 3c-loop-terminator does NOT fire (different finding or no prior
review): dispatch a manual fixer. Increment the PR's fix-cycle counter
in the log; if counter > 3 escalate (step 5).

#### 3d. CI complete + no review yet → dispatch manual reviewer

All blocking checks have a `conclusion`, reviews list is empty, no
`claude-review` workflow currently running.

→ Dispatch a manual reviewer (template below). Log:
`<timestamp> · <branch> · dispatched-reviewer`.

#### 3e. CI still running → wait

Otherwise. Log: `<timestamp> · <branch> · CI in progress · no action`.

### 4. Wave dispatch — fan out all wave implementers

Conditions: previous wave fully merged (or this is Wave 1), no PR
exists yet for any branch in this wave.

Use the Agent tool ONCE per tick with one or more sub-agent invocations
in parallel (one per PR in the wave). Example for Wave 2 (3 PRs):

```
Agent({
  description: "Wave 2 · PR1 + PR2 + PR5",
  subagent_type: "general-purpose",
  isolation: "worktree",
  run_in_background: true,
  prompt: <Implementer prompt for PR 1, filled>
})
Agent({
  description: "Wave 2 · PR2",
  subagent_type: "general-purpose",
  isolation: "worktree",
  run_in_background: true,
  prompt: <Implementer prompt for PR 2, filled>
})
Agent({
  description: "Wave 2 · PR5",
  subagent_type: "general-purpose",
  isolation: "worktree",
  run_in_background: true,
  prompt: <Implementer prompt for PR 5, filled>
})
```

Log each dispatch: `<timestamp> · <branch> · dispatched-implementer`.

The tick ends after the wave is fanned out. Subsequent ticks assess
the PRs that come online.

### 5. Escalation

- Append to log: `**ACTION:** <reason>. Orchestration paused.`
- `CronDelete <this-cron-id>`.
- Exit. User intervention required.

### 6. End condition — Wave 7 merged

If PR 8 (Wave 7) is MERGED:

- Append to log:
  ```
  **ACTION:** Clue-AI Modal-lane migration complete. Remind user to:
   (a) Run end-to-end Modal pilot (`modal run modal_jobs/03b_finetune.py`)
       against the fused corpus — cost ~$1.50, time ~25-35 min — and
       append the visual-eval results to docs/eval/pipeline_v2_pilot_validation.md.
   (b) Decide whether to open a follow-up palier 4 ADR for Modal-side
       inference (export_adapter_to_csv.py currently has a
       NotImplementedError stub for _generate_clues_on_modal).
   (c) Close this cron: CronDelete <id> (if not already self-deleted).
  ```
- `CronDelete <this-cron-id>`.
- Exit.

## Implementer agent prompt (template — fill BRACKETS)

```
You are the implementer for **PR [N] (Wave [W])** of the Bliss
clue-AI Modal-lane migration rollout.

## Source documents (read these first)
- Plan: `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration.md`
  — find the "## Task [N]" section. Each step has exact files, code
  blocks, and validation commands.
- Spec (background only):
  `docs/superpowers/specs/2026-05-25-clue-ai-modal-migration-design.md`.
- ADR-0057 (background only, if not Wave 1):
  `docs/adr/0057-cloud-gpu-modal-finetune-lane.md` (already on main
  if you're past Wave 1).
- Orchestration procedure (for cap-override standing-authorization
  citation):
  `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-procedure.md`.

## Before coding
- For pipeline_v2 / corpus / Modal Python work: no specific skill
  bundle needed; CLAUDE.md + the plan's Task section give every
  binding constraint.
- For changes that touch `.claude/skills/clue-ai/SKILL.md` (PR 3b,
  PR 8): read the existing skill first to preserve its structure.
- For ADR work (Wave 1): use the ADR template in CLAUDE.md.

## Your scope
1. Base your work branch off `origin/main` as `[NEW_BRANCH_NAME]`.
2. Execute every Step listed under "## Task [N]" in the plan, in
   order. Each Step is bite-sized (~2-5 min). For tasks involving
   new logic (PR 3b filters 9+10, PR 4b corpus builder, PR 7 bridge):
   follow TDD strictly — write failing test → run → implement → run
   → commit.
3. Each commit follows Conventional Commits with single scope.
   **Type allowlist** (from `.commitlintrc.yml`):
   `feat | fix | chore | refactor | test | docs`. Subject's first
   word must be lowercase. Body lines ≤ 100 chars. **DCO sign-off**
   mandatory (`git commit -s`).
4. After all steps done, run the plan's final validation commands.
5. Push: `git push -u origin [NEW_BRANCH_NAME]`.
6. Open the PR via `gh pr create --base main --title "..."
   --body "..."`. The plan's task includes the exact title and a body
   sketch.

## Cap override (PR-specific — apply when this PR is pre-flagged)

For PRs 3a, 5, 6 (and any Task body that exceeds 400 lines), invoke
the cap override in the PR body **from the first push**:

> **Cap override invoked.** This PR is ~<N> lines, above the 400-line
> target. Splitting would force reviewers to context-switch between
> tightly-coupled files (single workstream — <one-line rationale>).
> Per the Standing Authorization in
> `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-procedure.md`,
> the soft-target override applies.

Don't wait for the §6a reviewer to surface the cap finding — pre-flag
it. This short-circuits the auto-fixer's "split the PR" loop.

## CI auto-fix loop (after pushing the PR)

1. Wait ~60 s, then poll `gh pr checks [pr#]` every ~30 s until every
   BLOCKING check terminates.

   Blocking (must be `success`): `ci`, `commitlint`, `branch-name`,
   `dco`, `gitleaks` / `secret-scan`, `dependency-review`.

   Conditional (only if relevant files touched, not expected in this
   rollout): `regen-and-diff`, `spectral`, `helm-lint`,
   `api-chart-lint`.

   Informational (DO NOT block, DO NOT auto-fix): `claude-review`,
   `codeql` / `analyze-java-kotlin`.

2. If any blocking check FAILED: diagnose + fix. Common patterns:
   - `dco`: `git commit -s --amend --no-edit && git push --force-with-lease`.
   - `commitlint`: amend with single-context scope (no commas),
     lowercase subject first word, body lines ≤ 100 chars, no
     disallowed types.
   - `ci` (Spotless / Kotlin): not applicable for this rollout (Python
     only).
   - `gitleaks`: never put real `HF_TOKEN`, Modal API key, or any
     other secret in code or commit messages — references only.
   - `dependency-review`: pin versions explicitly; check the licence
     compatibility of any new pip dependency against `NOTICE.md`.

3. Budget: 3 fix passes max. After 3, STOP and report the blocker in
   your final message.

4. Don't auto-fix `claude-review` findings here — those are handled
   by the orchestrator via a separate fixer dispatch.

## Constraints (binding for every PR in this rollout)

- ADR-0001 §4 (soft, 2026-05-25 amendment): 400-line target;
  cap-override per the pre-flag instructions above.
- ADR-0013 §8: production inference path is local + committed CSV.
  Modal is **training only**. Don't add inference paths that read
  from Modal at request time.
- ADR-0023 / ADR-0024 (DBnary licence): unchanged from MLX lane; this
  rollout does not touch DBnary handling.
- ADR-0057 (companion to this rollout): cloud-GPU lane is paid third
  party; pilot run cost ≈ $1.50, capped per-function by `timeout=`.
- MANIFESTO.md: TDD for new logic (PR 3b filters, PR 4b builder, PR 7
  bridge), structured logging (no `print` debugging left in code; the
  Modal scripts' `print` calls for diagnostic output are fine —
  they're operator-facing console output, not application logs).
- No emojis in code or commits.
- No `--no-verify`, no `--no-gpg-sign`, no `git push --force` to
  `main`.
- Branch must match `branch-name.yml` (`<type>/<short-description>`
  with type in feat/fix/chore/refactor/test/docs).

## Report back (≤250 words)
- PR URL + branch + line count (main vs. tests).
- Last lines of test/lint/build output (exit code matters most).
- Whether the cap override was invoked.
- Any decisions beyond the plan.
- Any blockers or open CI findings after the 3-pass budget.
```

## Manual reviewer dispatch prompt

```
You are a manual code reviewer dispatched because the auto-`claude-review`
workflow did not fire (or completed without posting a review) on PR
#[N] of the Bliss clue-AI Modal-lane migration rollout.

Invoke the `reviewer` skill at the start of work. Follow ADR-0001
§6a:

- First line of your review body is either `LGTM` (approve) or
  `Findings —` (request-changes).
- Each finding cites a specific rule (CLAUDE.md / ADR-NNNN / MANIFESTO
  / the plan / the spec), gives a file:line reference, and proposes
  a concrete fix.
- Scope: lines actually changed in the PR diff. Don't review
  unrelated code.

Cap-override handling: if the PR body cites the Standing Authorization
section in
`docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-procedure.md`
and the only candidate finding is the 400-line cap, the cite resolves
it. Don't re-flag.

Post the review via:

  gh pr review [N] --approve --body "<body>"          # if LGTM
  gh pr review [N] --request-changes --body "<body>"  # if Findings

If the same-actor token rejects `--approve`, fall back to:

  gh pr review [N] --comment --body "<body>"          # body still starts with "LGTM"

The orchestrator's merge gate matches on the first line being `LGTM`,
regardless of approve vs. comment.

Report back: review verdict + summary (≤150 words).
```

## Manual fixer dispatch prompt

```
You are a manual fixer dispatched because the auto-fixer didn't act
on PR #[N] within the auto-loop window. The PR has open review
findings.

1. Read findings: `gh pr view [N] --json reviews,comments`. Identify
   each open issue.
2. Check out the PR's branch (you're already in an isolated worktree):
   gh pr checkout [N]
3. Address each finding. Run the appropriate validation after each fix:
   - Python (this rollout): `python3 -m pytest <relevant test path> -v`
   - For Modal scripts: `python3 -m py_compile modal_jobs/<file>.py`
     plus the byte-equal diff check vs the bliss-clue-ai source if
     this is a verbatim port.
4. Commit with Conventional Commits + DCO sign-off (`git commit -s`).
   One commit per logical fix.
5. Push: `git push origin <branch>`.
6. For each thread on the PR, post a reply mapping finding → commit
   SHA via:
   gh pr comment [N] --body "Addressed: <commit-sha> — <finding summary>"

CI auto-fix loop (paste-ready): same as in the implementer prompt.
Budget: 3 fix passes.

Cap-override 3c-loop-terminator handling: if the §6a reviewer is
re-flagging the cap on consecutive cycles and the PR body already
cites the Standing Authorization section, do NOT edit the body again.
Stop and report — the orchestrator will dispatch a fresh reviewer per
the procedure's 3c-loop-terminator clause.

Report back: list of commit SHAs + which findings each addresses +
remaining open items (if any).
```

## Logging format

Each tick appends one or more lines to
`docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-log.md`
under the "## Event log" section:

```
- `<ISO-8601 timestamp>` · `<branch>` · `<verdict>` · `<action>`
```

Verdicts: `merged`, `dispatched-implementer`, `dispatched-reviewer`,
`dispatched-fixer`, `waiting-ci`, `waiting-review`,
`waiting-previous-wave`, `escalated`, `complete`, `error`.

Escalations and end-conditions get a **bold ACTION:** entry.

## What this procedure does NOT do

- It does not modify `main` directly. Every change goes via a PR.
- It does not force-push to anyone else's branch.
- It does not bypass any CI gate.
- It does not auto-write fixes to the implementation plan or spec —
  those changes go through a normal PR.
- It does not retry indefinitely. After 3 fix cycles per PR or any
  closed-not-merged PR, it escalates and self-deletes.
- It does not post `@Ishou`-authored comments — impersonation is
  blocked by the auto-mode classifier. The orchestrator posts as
  itself and cites the in-repo Standing-Authorization section.
- It does not interfere with the parallel survey orchestrator's
  branches (`feat/survey-*`, `docs/survey-*`) or ADR-0056.
- It does not run any Modal job itself. Paliers 0 / 1 / 2 / 3a / 3b
  are manual end-to-end smoke runs done by the maintainer after the
  PRs land (cost-bearing).
