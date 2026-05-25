---
name: dispatch
description: Orchestrate parallel-agent work for the Bliss multi-PR rollouts (e.g. the multiplayer "game" feature, the mobile custom keyboard rollout). Use when the user asks you to dispatch agents in waves, fix CI on an open PR, relaunch a fixer/reviewer loop, pick up where the previous dispatcher left off, OR set up the cron-driven autonomous orchestration mode for a new feature. Encodes the conventions, prompt templates, CI gates, common failure modes, wave-dependency map, and the autonomous-cron tick procedure for this repo.
---

# Bliss dispatcher playbook

This skill is for the **orchestrator** role: a Claude session that doesn't write code itself but dispatches sub-agents (via the `Agent` tool with `isolation: "worktree"`) to do parallel multi-PR work. Refresh your memory from this file at the start of every session that involves opening, fixing, or shepherding more than one PR.

## When this skill applies

- The user describes a feature spanning many files, languages, and bounded contexts (e.g. "lobbies + multiplayer game", "new bounded context").
- The user references a plan file at `docs/superpowers/plans/<name>.md` and expects you to follow it.
- The user mentions "waves", "batch work", "parallel agents", "fixer loop", or asks you to "relaunch a reviewer".
- An in-flight PR is failing CI and the user asks you to fix it (or the auto-fix loop hit its cap).
- The user invokes `/orchestrate`, asks for the "cron orchestration", or wants the dispatcher to "be autonomous" across PR cycles — see [Cron-driven autonomous orchestration](#cron-driven-autonomous-orchestration) below.

If the task is a single straightforward edit, **do not invoke this skill** — just edit the file. This is for orchestration only.

## Anchor documents

Read these before doing anything substantial. They override anything in this skill.

- `CLAUDE.md` — project-wide engineering rules (binding for every session).
- `MANIFESTO.md` — rationale behind those rules.
- `docs/adr/0001-parallel-agent-development-workflow.md` — **the single most important ADR** for dispatchers. §4 sets the 400-line PR target (soft, per the 2026-05-25 amendment — not a hard cap), §6 sets the implementer-≠-reviewer pattern, §6a sets the max 5 review passes plus the identical-finding loop-termination rule (2026-05-25), §7 sets the "ADR-before-implementation" gate.
- `docs/adr/0003-cross-language-api-contract.md` — schema-first; OpenAPI/AsyncAPI before code; UUID v7 / ISO-8601 / camelCase / RFC 7807 / explicit `required` and `nullable`.
- `docs/adr/0006-jvm-http-framework.md` — Ktor + Kotlin; SSE for v1, WebSocket deferred until multiplayer (the multiplayer rollout resolves that defer).
- `docs/adr/0009-self-managed-k8s-deployment.md` — Helm chart layout + CNPG.
- `docs/adr/0018-game-bounded-context-and-realtime.md` — multiplayer architecture.
- The current plan file (`docs/superpowers/plans/<date>-<slug>.md`) — the wave-by-wave rollout map.

## Bounded contexts

| Context | Layout | Stack | Owns |
|---|---|---|---|
| `grid/` | `domain/`, `application/`, `infrastructure/`, `api/`, `worker/` | Ktor 3.x, Kotlin, Postgres (CNPG), Flyway | Stateless puzzle generation; word corpus |
| `game/` (introduced by multiplayer rollout) | same | Ktor + WebSocket | Lobbies, players, real-time game state (in-memory v1) |
| `frontend/` | `domain/`, `application/`, `infrastructure/`, `ui/` | Vite + React 19 + TanStack Router + Panda CSS | All UI |

**Cross-context imports are FORBIDDEN** (CLAUDE.md, ADR-0001 §1). `game:domain` does not import `com.bliss.grid.*`. Schema reuse via cross-file `$ref` (e.g. `grid/api/openapi.yaml#/components/schemas/Puzzle` from `game/api/asyncapi.yaml`) is **also forbidden** — duplicate the shape with a comment citing the canonical source.

## Wave-based rollout pattern

A "wave" = a group of PRs that can be implemented and reviewed in parallel because they touch disjoint files. Waves are sequenced by dependency.

### How to read the plan's wave table

```
| Wave | Parallel PRs | What they do                                        |
|------|--------------|-----------------------------------------------------|
| A    | #1, #2, #3   | ADR + AsyncAPI/OpenAPI schemas (schema-first)       |
| B    | #4, #14, #18 | Domain types + independent frontend helpers         |
| C    | #5           | Application layer (sequential — depends on domain)  |
| ...  | ...          | ...                                                 |
```

Schema PRs (Wave A) are a **hard barrier**: they MUST land before any implementation PR opens (ADR-0001 §3).

### Dispatch one wave at a time

After a wave is fully merged on `main`, dispatch the next. Don't dispatch Wave N+1 while Wave N is in flight — the agents would rebase or miss schema/domain types.

Inside a wave, dispatch all PRs **in a single assistant turn** (one message, multiple `Agent` tool uses). They run truly concurrently.

## Agent dispatch protocol

### Tool: `Agent`

```
Agent({
  description: "Wave X · #N <one-line>",
  subagent_type: "general-purpose",
  isolation: "worktree",
  prompt: "<full self-contained prompt>"
})
```

`isolation: "worktree"` is non-negotiable for write-PR work — every agent gets its own checkout to avoid stomping on each other's branches.

### Self-contained prompts

The agent has zero conversation history. Every prompt MUST include:

1. **Identity + scope**: "You are an implementation agent. PR #N of Wave X — <one-paragraph goal>."
2. **Background**: link to plan file, link to relevant ADRs, name relevant existing modules. The agent will read them.
3. **What to do**: exact file paths and the changes expected. Don't be vague.
4. **What NOT to do**: scope caps. "Don't touch grid:application from this PR." "Don't add new deps." "Don't refactor X."
5. **How to ship**: branch name, validation commands (gradle / pnpm), commit message template, PR title + body shape, the `mcp__github__create_pull_request` call.
6. **Constraints**: ADR-0001 §4 cap, conventional commits, DCO sign-off (`git commit -s`), no emojis.
7. **Domain skill pointer** — tell the agent to invoke the relevant playbook at the start of work (see "Domain-specific skills" below). Saves you copy-pasting 100s of lines of conventions per prompt.
8. **CI auto-fix loop** (paste-ready snippet — see below).
9. **Report-back contract**: max ~250 words, branch + PR URL, line counts, test/lint/build outputs, decisions beyond the prompt, blockers.

### Domain-specific skills

Three playbooks encode the per-area conventions so you don't have to repeat them in every agent prompt. Tell the agent to invoke whichever applies before starting work — e.g. include this line in the prompt:

> Before you begin, invoke `/jvm-backend` (or `/frontend`, `/schemas`) for the conventions and gotchas specific to this work.

| Skill | Triggers when work touches | Encodes |
|---|---|---|
| `frontend` | `frontend/**` | Vite/React 19/TanStack Router/Panda/Vitest, hexagonal layering enforced by eslint-plugin-boundaries, ADR-0002 §4 uncontrolled-input contract, MSW preview-only rule, generated-types regen, jsdom polyfills. |
| `schemas` | `**/openapi.yaml`, `**/asyncapi.yaml` | ADR-0003 §6 wire conventions (UUID v7, ISO-8601, RFC 7807, camelCase, x-enum-varnames), the absence-≠-null rule, AsyncAPI 2.6 exception (ADR-0019), LobbyId base58 nanoid exception (ADR-0018 §5), no cross-context $ref, Spectral commands, regen-and-diff workflow. |
| `jvm-backend` | `grid/**`, `game/**`, `*.gradle.kts`, `settings.gradle.kts` | Bounded-context module layout, Konsist-enforced layer rules, no cross-context imports, ASCII-only test names, Spotless/ktlint, build commands, the **settings-meets-Dockerfile gotcha** that has bitten three PRs in this rollout, TOCTOU rules for `repo.mutate`, configuration-cache pitfalls. |

The skills are project-level (`.claude/skills/<name>/SKILL.md`) so every agent dispatched in a worktree auto-loads the descriptions. They invoke the body on demand when their task matches.

### Branch + commit conventions

- **Branches**: `<type>/<short-description>` where `<type>` is one of `feat|fix|chore|refactor|test|docs`. Enforced by the `branch-name` CI check. The Claude bot uses the `chore/claude-` prefix (e.g. `chore/claude-game-domain-scoring`) so its branches satisfy the conventional-type requirement per CLAUDE.md. (Historical note: ADR-0001 §2 used `claude/<context>-<slug>-<id>`; CLAUDE.md is the current authority.)
- **Commit messages**: Conventional Commits, single scope (no commas — commitlint rejects `fix(grid-api,grid-worker):`). Use `fix(grid):` if the change spans multiple submodules of the same context.
- **Commit type allowlist**: `.commitlintrc.yml` `type-enum` is closed: `[feat, fix, chore, refactor, test, docs]`. **`perf`, `style`, and `wip` are NOT allowed** — PR #368 took three review cycles on `perf:`/`style:` (commits `perf(grid): …` then `style(grid): spotless …`). Use `refactor:` for algorithm/perf tweaks; `chore:` for auto-format; **`chore(<scope>): wip ...` for work-in-progress** (`wip(...)` as the type is rejected). No `build:` / `ci:` / `revert:` either.
- **DCO sign-off**: `git commit -s` adds `Signed-off-by: <name> <email>`. Required by the `dco` CI check. To fix a missing trailer: `git commit -s --amend --no-edit && git push --force-with-lease`.

## CI auto-fix loop (paste verbatim into every dispatched agent prompt)

```
After pushing the PR, monitor CI and auto-fix until green.

1. Wait ~60 s, then poll `mcp__github__pull_request_read` (`get_check_runs`)
   every ~30 s until every BLOCKING check has terminated.

   Blocking checks (must be `success` before reporting back):
     build, submit-gradle, commitlint, branch-name, dco, gitleaks,
     dependency-review, regen-and-diff, spectral

   Informational (do NOT block on, do NOT auto-fix):
     claude-review, Analyze (java-kotlin) / CodeQL

2. If any blocking check FAILED, diagnose + fix. Common patterns:

   - dco              → git commit -s --amend --no-edit; force-push
   - commitlint       → amend with single conventional scope (no commas)
   - build (gradle)   → ./gradlew :<scope>:check locally; fix; push
   - build (frontend) → cd frontend && pnpm typecheck && lint && test && build
   - regen-and-diff   → pnpm api:generate; commit the diff
   - spectral         → fix schema lint findings; re-run npx spectral lint

3. Budget: 3 fix passes max. After 3, STOP and report the blocker.
   Do NOT grind on a flaky check or wedged toolchain.

4. claude-review findings: address with the same 3-pass budget. If the bot
   still reports findings after pass 3, stop and report the open list.

5. Only report back once all blocking checks are green (or budget exhausted).
```

## ADR-0001 §6a review-fix cycle (when the orchestrator dispatches it)

The auto-fix loop above is bot-CI only. For human or `claude-review`-bot review feedback that lands AFTER the agent has reported back, dispatch a **separate fixer agent**:

1. Use `mcp__github__pull_request_read` (`get_reviews`) to fetch the review body.
2. Spawn a new general-purpose agent in a worktree with the open findings as the brief.
3. The fixer addresses each finding, pushes, replies on the review thread (or top-level comment if no thread IDs) mapping finding → commit SHA.
4. Cap: 5 review passes total per ADR-0001 §6a. After that, stop and escalate.

Per ADR-0001 §6a, the **implementer is not the reviewer**. If you need a manual review on top of `claude-review`, dispatch a separate reviewer agent — never the same one that wrote the code.

## Common failure modes (and the canonical fix)

| Symptom | Root cause | Fix |
|---|---|---|
| `InvalidPathException: Malformed input or input contains unmappable characters` writing `…$test name with — em-dash$1.class` | Em-dash (`—`) in a Kotlin `@Test fun \`...\`` name; CI's POSIX locale can't encode it as a file path | Replace `—` with ASCII `-`. JVM-arg workarounds (`-Dfile.encoding=UTF-8`, `-Dsun.jnu.encoding=UTF-8`) don't reliably help — the rename is the durable fix. |
| `Configuring project ':game:domain' without an existing directory` during Docker build | `settings.gradle.kts` includes a module whose directory is missing in the build container | Add `COPY game/domain/build.gradle.kts game/domain/` (and any sibling new modules) to every Dockerfile that runs `./gradlew :…:shadowJar`. Source isn't needed if the task being run doesn't depend on it. |
| `commitlint` fails | Multi-scope (`fix(grid-api,grid-worker):`) | Use single hyphenated scope (`fix(grid):`). Amend + force-push. |
| `dco` fails | Missing `Signed-off-by:` trailer | `git commit -s --amend --no-edit && git push --force-with-lease`. For multi-commit branches: `git rebase --signoff origin/main`. |
| `regen-and-diff` fails | OpenAPI changed but generated TS types not regenerated | `cd frontend && pnpm api:generate`; commit the diff. The generated file is excluded from the line target per ADR-0001 §4. |
| `spectral` fails | New AsyncAPI/OpenAPI lint violation | Fix the schema. `npx -y @stoplight/spectral-cli@latest lint <file> --ruleset=@asyncapi/spectral-ruleset` or the OpenAPI ruleset. |
| ADR number collision | Two PRs picked the next ADR number simultaneously | One PR keeps the original number; the other renumbers to the next free slot. Update title, all in-document refs, and references in companion specs. |
| `claude-review` flags "ADR-XXXX missing from this branch" | The schema PR references an ADR that lives only on a sibling PR | Cherry-pick the ADR file onto this branch (`git checkout origin/<sibling-branch> -- docs/adr/XXXX-*.md`). When sibling lands first, conflict-merge is a clean overlap. |
| Cross-context `$ref` flagged | AsyncAPI references `grid/api/openapi.yaml#/...` | Inline the schema with a "mirrors grid/api/openapi.yaml; ADR-0001 §1 forbids cross-context $ref" comment. Update both files together when the wire format evolves. |
| `nullable: true` field absent from `required` | ADR-0003 §6: absence ≠ null | Add the field to `required`. The wire always sends the field; `null` is the explicit blank value. |
| `commitlint` rejects `perf:` or `style:` | Type not in the `type-enum` allowlist | Rebase to `refactor:` (algorithm/perf tweak) or `chore:` (auto-format). PR #368 burned 3 cycles on this. |
| Reviewer flags "ADR-0001 §7 violation: no ADR for this change" | Architectural change shipped without a preceding ADR PR | Open an ADR-only PR first (`docs(adr-NNNN): …`), merge it, then rebase implementation on top. Plans under `docs/superpowers/plans/` are NOT decision records — they don't satisfy §7. PRs #350, #352, #361, #368, #370, #386 all hit this. |
| Reviewer flags "ADR-NNNN referenced but does not exist on main" | Implementation cites an ADR that lives only on a sibling/unmerged PR | Cherry-pick the ADR file from the sibling branch, OR wait for the sibling to merge before opening the dependent PR. PR #370 burned 2 cycles. |
| Reviewer flags "deploy from dev workstation" in PR body | Runbook directs `helm upgrade` from a laptop | Replace with `workflow_dispatch` against the deploy workflow at the merge SHA. CLAUDE.md "CI is the only path to production." PRs #350, #352. |
| Reviewer flags "mutable image tag" in `values.yaml` | Tag like `16.10-bookworm` without `@sha256:…` digest | Pin to digest. Helm subchart deps MUST commit `Chart.lock`. A bare version is not a pin. PRs #349, #361. |
| GHA matrix step `if: matrix.X != ''` silently skipped on every row | Row omits `X`; undefined matrix keys evaluate to `''` | Declare the key in EVERY matrix row (use `""` for rows that should skip). Missing key = silent dead code, not "skip". PR #406 cycled 3 times. |
| Reviewer flags "cross-bounded-context PR" on a "trivial" dep bump / Dockerfile patch touching both `game/` and `grid/` | ADR-0001 §1 applies even when the diff is small | Split into two PRs by context. Don't argue "it's only 20 lines, the §4 target is fine" — §4 doesn't override §1. PRs #330, #331, #356, #366, #379 all hit this. |
| Reviewer asks for added tests; adding them pushes past the 400-line target | Fixer pads instead of splitting | Default: land tests in a follow-up PR immediately after, citing the original. Per ADR-0001 §6a rule 6 as amended 2026-05-25: splitting remains the default, but if the test additions are part of the same coherent workstream and splitting would create a dependent follow-up PR, the tests ship in-PR and the body cites the §4 soft-target exception. PR #381 was the worked example for "split"; PR #614 (survey-domain, 2026-05-25) is the worked example for "ship coherent layer past target". |
| ADR / committed doc references an absolute local path (`/Users/…/`) | Path is meaningless outside the author's machine | Inline the content or use a repo-relative path. ADRs are durable — local paths rot on push. PR #369. |

## Frontend conventions

- Stack: Vite + React 19 + TanStack Router + Panda CSS + Vitest. ADR-0002 §4: inputs are uncontrolled.
- Hexagonal layers: `src/domain/`, `src/application/`, `src/infrastructure/`, `src/ui/`. eslint-plugin-boundaries enforces the layering — don't violate it.
- Generated OpenAPI types live at `src/infrastructure/api/<context>/types.ts`. CI's `regen-and-diff` job rebuilds them and fails if stale.
- Tests use Vitest + Testing Library. `pnpm test --run tests/<file>.test.tsx` for a single file.

## Backend (JVM) conventions

- Stack: Ktor 3.x, Kotlin, Postgres (CNPG), Flyway, kotlinx-coroutines, Konsist, JUnit 5, AssertJ, Kotest, Testcontainers.
- Module layout: `<context>/{domain,application,infrastructure,api,worker}` as separate Gradle modules.
- Dependencies STRICTLY one-way: `api → infrastructure → application → domain`. Konsist tests in each module enforce this.
- `domain/` modules have ZERO framework deps. `application/` may use kotlinx-coroutines + slf4j-api but no Ktor.
- Build: `./gradlew :<context>:<module>:test :<context>:<module>:spotlessCheck`. Full repo: `./gradlew check`.
- Spotless ktlint runs on commit; `./gradlew spotlessApply` to auto-fix.
- Test names with non-ASCII characters (em-dashes, smart quotes) WILL break the build under POSIX-locale CI runners. Use ASCII only.

## Tools you (the orchestrator) actually use

- **`Agent`** — dispatch with `isolation: "worktree"` for writes. Send multiple in one message for parallel.
- **`mcp__github__pull_request_read`** — `get_check_runs`, `get_reviews`, `get_review_comments`, `get_status`, `get_diff`. Don't fetch raw GitHub Actions logs (no MCP tool for that — agents reproduce locally instead).
- **`mcp__github__create_pull_request`** — agents call this themselves; you only call it if you're opening a fixer PR yourself.
- **`mcp__github__add_issue_comment`** / **`add_reply_to_pull_request_comment`** — for orchestrator-side replies; agents handle their own comments.
- **`Bash`** — for `git fetch`, branch state checks, local validation, `git worktree remove -f -f` to free locked agent worktrees after they finish.
- **`TodoWrite`** — track wave progress. Update on every transition (dispatched → opened → merged).
- **`SendMessage` (when surfaced)** — to redirect a still-running agent. Useful when you spot a problem in flight (e.g. "PR X just merged on main; abort the cherry-pick step").

## Concrete prompt template

Use this skeleton for every Wave agent. Adapt the bracketed sections.

```
You are an implementation agent. **PR #<N> of Wave <X>** in the
<feature> rollout: <one-paragraph goal>.

## Background
Plan: `docs/superpowers/plans/<plan>.md`. Read §<sections> first.
Relevant ADRs: <list>. Relevant existing modules: <list>.

## Your scope
1. <step 1 with exact paths>
2. <step 2>
...

DO NOT: <scope caps>.

## How to ship
1. Branch off `origin/main` as `<type>/<short-slug>`.
2. Implement.
3. Validate locally:
   - `./gradlew :<context>:<module>:check :<context>:<module>:spotlessCheck`
   - or `cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm build`
4. Commit with `git commit -s` and a Conventional Commit message:
   ```
   <type>(<scope>): <subject>

   <body explaining why; reference ADRs and the plan>
   ```
5. Push: `git push -u origin <branch>`.
6. Open a PR via `mcp__github__create_pull_request` (owner `ishou`,
   repo `bliss`, base `main`). Title = subject of commit. Body =
   Why / What / Test plan; reference Wave <X> · PR #<N>.

## Constraints
- ADR-0001 §4: 400-line target on meaningful changes (generated code excluded). Soft per 2026-05-25 amendment; cite the override in the PR body when the workstream warrants it.
- Conventional commits, DCO sign-off (`git commit -s`).
- No emojis.
- No cross-context imports.
- <other context-specific constraints>

## CI auto-fix loop
<paste the loop block from this skill verbatim>

## Report back (under 250 words)
- Branch + PR number + URL.
- File inventory + total LOC (main vs tests).
- Test/lint/build outputs.
- Any decisions beyond the brief.
- Any blockers.
```

## Cron-driven autonomous orchestration

The wave pattern above assumes a human-in-the-loop dispatcher. The **autonomous mode** runs the same patterns from a cron-fired prompt every 2 minutes, so the rollout progresses without the user re-engaging Claude between PR cycles.

Used for the mobile custom keyboard rollout (2026-05-21 through 2026-05-22), 14 PRs across 2 days, ~3 manual interventions total.

### When to use autonomous mode

- The user explicitly asks for autonomy ("be autonomous", "self-paced", "/orchestrate", "ping me when done").
- The rollout has 5+ phases that map to a predictable phase sequence.
- The user is the maintainer and can grant in-session standing authority for cap-overrides + merge decisions.

### Architecture

Three checked-in artefacts + one cron:

1. **Orchestration procedure file** (`docs/superpowers/plans/<date>-<slug>-orchestration-procedure.md`): the cron's source-of-truth. Tick decision tree, agent prompt templates, phase map, escalation rules, standing authorization. Living document — patch it via small follow-up PRs as patterns evolve.
2. **Orchestration log file** (`docs/superpowers/plans/<date>-<slug>-orchestration-log.md`): append-only event ledger. Initial standing decisions, then per-event entries (dispatched / merged / waiting / escalated). For human review.
3. **Spec + plan files** (the feature's actual blueprint), authored by the brainstorming + writing-plans skills.
4. **`CronCreate` job** with `*/2 * * * *`, `recurring: true`, `durable: true` (note: the `durable` flag is currently silently ignored by the runtime; the cron is effectively session-only). The cron `prompt` is a small bootstrap that reads the procedure file and executes one tick.

### Tick decision tree (the cron's `prompt`)

The cron `prompt` instructs the spawned session to:

1. `cd $(git rev-parse --show-toplevel) && git fetch origin --quiet`.
2. Load the procedure file. If not on `origin/main` yet (spec PR not merged), read it from the spec branch via `git show origin/<spec-branch>:<path>`.
3. Walk the procedure's phase map in order. For each phase, check its PR:
   - **MERGED** → next phase.
   - **CLOSED-not-merged** → escalate (log + `CronDelete` self + exit).
   - **OPEN** → assess via the decision tree below.
   - **No PR + previous phase MERGED** → dispatch the implementer agent for this phase.
4. Take at most one action per tick.

#### Open-PR decision tree

Apply top-down; act on the first match:

- **3a. Ready to merge.** All blocking checks `SUCCESS` (`ci`/`build`/`frontend-build`, `commitlint`, `branch-name`, `dco`, `gitleaks`, `dependency-review`, `regen-and-diff`, `spectral`/`openapi-lint`, `helm-lint`, `api-chart-lint`) AND `mergeable: MERGEABLE` AND `mergeStateStatus != BLOCKED` AND one of: most recent review body starts with `LGTM` (case-insensitive) **OR** the only outstanding findings are about the 400-line target AND the PR body cites the §4 2026-05-25 soft-target override **OR** `3c-loop-terminator` (below) fired and the verdict was effectively-resolved. → `gh pr merge <pr#> --squash` (avoid `--delete-branch` — it triggers a local git op that fails when agent worktrees hold `main`).
- **3b. Auto-loop alive.** `claude-review` check is `IN_PROGRESS` or `QUEUED`, or there's an active `Claude Code Review` workflow run on the branch within the last 15 min. → wait, no action.
- **3c. Findings + no fixer activity.** Latest review starts with `Findings —`, AND no `claude-review` workflow running on the branch right now, AND no commit on the branch since the review timestamp.
  - **3c-loop-terminator**: BEFORE dispatching a manual fixer, compare the latest review's findings to the **prior** §6a review's findings. If the **first finding's rule-citation + claim are essentially identical** to the prior cycle's first finding (e.g. both flag "400-line cap exceeded" with the same proposed split, both flag "Finding X re-opened" with no material change in claim), AND the diff has materially changed between the two reviews (a fix commit landed, or the PR body was edited), the auto-fixer loop is stuck — the reviewer keeps re-flagging something the fixer cannot or should not address by code change. **Terminate the auto-fixer loop:**
    1. If the repeated finding is the **400-line target**: dispatch a body-edit fixer that updates the PR body to cite the §4 2026-05-25 soft-target override (or the procedure's standing-authorization section), then dispatch a manual reviewer for a fresh verdict.
    2. If the repeated finding is **anything else** (a genuine code finding the fixer cannot resolve): escalate per step 5 — append `**ACTION:** identical finding on cycles N-1 and N for PR <pr#>; auto-loop terminated, human intervention required` and `CronDelete` self.
  - If 3c-loop-terminator does NOT fire (the finding is materially different from prior cycle, or there's no prior review to compare against): dispatch a manual fixer (template below).
- **3d. CI complete + no review yet.** All blocking checks have a `conclusion`, reviews list is empty, no `claude-review` workflow currently running. → dispatch a manual reviewer (template below).
- **3e. CI still running.** Otherwise → wait.

**Informational checks (NEVER block merge):** `claude-review` itself (it posts findings as comments, not as required check), `CodeQL` / `Analyze (java-kotlin)`, `deploy` (Cloudflare Pages preview).

#### Identical-finding detection — heuristic

A finding from cycle N is "essentially identical" to one from cycle N-1 when:

- The cited **rule** (e.g. "ADR-0001 §4", "CLAUDE.md TDD") is the same, AND
- The cited **location** (file path, or "entire PR diff" for cap findings, or "PR description" for body-claim findings) is the same, AND
- The reviewer's recommended **fix** is the same shape (e.g. "split into PR A + PR B", or "add a test for X", or "remove the stale claim").

The fixer's reply text and the surface phrasing may differ — what matters is whether the *finding's structural identity* repeats. PRs #611 (orchestration procedure, cap finding) and #614 (survey-domain, cap finding) both hit this in the 2026-05-25 survey-module rollout; in both cases the §6a reviewer re-flagged the cap on every cycle because the implementation could not split and the implementer kept trying to rephrase the PR body instead of citing the override. The 3c-loop-terminator is the structural defense against that pattern.

### Standing maintainer authorization

The maintainer can grant in-session standing rules that unblock the orchestrator across phases. Record verbatim in the procedure file's "Standing maintainer authorization" section so the §6a reviewer can cite it.

Example (mobile-keyboard rollout): "for the 400 line-cap: i grant you explicit authorization to by-pass it if you deem it necessary, the 400 line-cap should trigger a question about 'should the PR be split?' but it does not mean that it should always be the case".

Reinforcement (survey-module rollout, 2026-05-25): "the 400 cap MAY be by-passed by YOUR call even without my call, i merged it but avoid anything like this later on" — the orchestrator has standing authority to invoke the override **proactively without escalating to the maintainer**, particularly to short-circuit the 3c-loop-terminator pattern above. The §4 soft-target framing in ADR-0001 (2026-05-25 amendment) codifies this at the rule level.

- Each implementer agent still asks "should this be split?" first. The override is the exception, not the default.
- When invoking the override, the PR body cites the §4 2026-05-25 soft-target amendment OR the procedure file's standing-authorization section. The §6a reviewer treats that citation as resolved — re-flagging it on the next cycle triggers the 3c-loop-terminator.
- **Pre-flag at dispatch:** for phases whose plan body marks them as cap-heavy (e.g., the survey-module plan pre-flagged PR4 + PR7), the orchestrator's implementer-agent prompt should instruct the implementer to invoke the soft-target override in the PR body **from the first push** — don't wait for the §6a reviewer to surface the cap finding before citing it.
- The orchestrator does NOT post `@<maintainer>`-authored comments — that's impersonation and the auto-mode classifier blocks it. Cite the in-repo grant; post the comment as the orchestrator with attribution.

### Manual reviewer dispatch (auto-loop hung)

```
Agent({
  description: "Manual reviewer for PR #N (auto-reviewer hung)",
  subagent_type: "general-purpose",
  isolation: "worktree",
  run_in_background: true,
  prompt: <see "Manual reviewer prompt template" in the orchestration procedure file>
})
```

The prompt invokes the `reviewer` skill, reads the diff, posts `LGTM, no findings.` or `Findings — ...` via `gh pr review`. Same-actor token may force a `--comment` fallback (instead of `--approve`); the merge condition still matches on `LGTM` as the first line.

### Manual fixer dispatch (findings without auto-action)

```
Agent({
  description: "Manual fixer for PR #N (auto-fixer hung)",
  subagent_type: "general-purpose",
  isolation: "worktree",
  run_in_background: true,
  prompt: <see "Manual fixer prompt template" in the orchestration procedure file>
})
```

Fixer addresses each open finding, pushes, comments on the PR mapping finding → commit SHA. Budget: 3 fix passes per phase; after that, escalate via log entry + `CronDelete`.

### Stacked-PR coordination

PR B stacked on PR A's branch: when A is squash-merged, B's history still carries A's pre-squash commits. B becomes `CONFLICTING`. Recovery:

```
git worktree remove .claude/worktrees/agent-<id> -f -f   # if locked
git checkout -B rebase-tmp origin/<B-branch>
git rebase --onto origin/main <last-commit-from-A> rebase-tmp
git push --force-with-lease origin rebase-tmp:<B-branch>
```

`<last-commit-from-A>` is the SHA of the last A-originated commit on B's history (find it with `git log --oneline origin/main..<B-branch>` and identify where A's commits end and B's begin). The rebase drops the duplicate A commits; CI re-runs cleanly.

This recipe ran 3 times in the mobile-keyboard rollout (#583, #585, #586 each at least once). Reliable.

### Watchdog (optional)

A bash script that polls GitHub PR state independently of Claude, popping a desktop notification if PR activity stalls > 20 min while at least one PR is open. Doesn't restart Claude (can't), but alerts the maintainer to re-engage if needed. Lives in the maintainer's home, not in the repo.

### End condition

When the last phase merges:
- Append `**ACTION:** rollout complete. Remind user to <recover stashes / close cron / etc.>.`
- `CronDelete <cron-id>` to stop future ticks.
- Exit.

### Bootstrap via `/orchestrate`

The `/orchestrate <spec-file>` slash command generates the procedure + log files, sets up the cron, and surfaces any pre-flight items (e.g., uncommitted stashes, watchdog script path). See `.claude/commands/orchestrate.md` for the bootstrap template.

## Worktree hygiene

Agents that finish leave their worktree at `.claude/worktrees/agent-<id>/` (repo-relative) with the branch checked out. To free a branch (so you can `git checkout` it from the main repo):

```
git worktree remove .claude/worktrees/agent-<id> -f -f
```

The double `-f -f` overrides the agent-process lock. Worktrees with no commits auto-clean on agent exit; ones with commits persist until manually removed.

## Failure-recovery patterns I've actually used

- **Two ADRs both number 0018** (PR #121 + PR #122 collision): renumbered the second to 0019, updated all in-doc references, force-pushed.
- **Auto-reviewer flagged "ADR-0018 missing from branch"** even though it was on main (PR #121 had merged): cherry-picked the ADR file onto the dependent branch so the review on the standalone branch resolves cleanly.
- **PR #126 broke `grid/api` Docker build** because `settings.gradle.kts` got a new `:game:domain` include and the `grid/api/Dockerfile` only `COPY`d grid modules: added `COPY game/domain/build.gradle.kts game/domain/` mirroring the existing `:grid:worker` workaround.
- **Em-dash in `@Test` names** crashed `compileTestKotlin` under POSIX-locale CI: renamed to ASCII; rejected `-Dfile.encoding=UTF-8` because the JVM reads `sun.jnu.encoding` before `-D` flags apply.
- **`commitlint` rejected `fix(grid-api,grid-worker):`**: amended to `fix(grid):` (single scope per spec).
- **Stale stash in `git stash list`** from a prior dispatcher session: drop after confirming the PR that supersedes it has been opened.
- **`commitlint subject-case` rejected `feat(frontend-grid): LetterPreview reserves ...`** (PR #584): subject's first word was PascalCase. Fix: rewrite history via the cherry-pick rebase recipe in the "Cron-driven autonomous orchestration > Stacked-PR coordination" section. New subject `feat(frontend-grid): letter preview reserves ...` (lowercase + spaced). Force-push.
- **`commitlint body-max-line-length` rejected commit with > 100-char body lines** (PR #574 procedure commit): one line was 124 chars. Same cherry-pick rebase recipe to rewrite the message. Verify before pushing: `git log -1 --format=%B | awk '{print length, $0}'`.
- **Stacked PR became CONFLICTING after base squash-merge** (PR #583 stacked on #582; PR #585 stacked on #584; PR #586 stacked on #585): use `git rebase --onto origin/main <last-base-commit> <branch>` to drop the duplicate pre-squash commits. Force-push. CI re-runs cleanly. See "Stacked-PR coordination" above for the exact commands.
- **Auto-fixer loop hung after pushing a fix** (PR #582 cycle 2; PR #586 cycle 2): `claude-review` workflow run stuck `in_progress` > 15 min with no review posted. Dispatched a manual reviewer agent (see "Manual reviewer dispatch" above). Both ended in `LGTM` posted as a `--comment` (the same-actor token rejects `gh pr review --approve`); orchestrator's merge gate still matches on `LGTM` as the first line.
- **`gh pr comment` blocked for impersonation** when comment body started with "@Ishou here. Approving...": auto-mode classifier flagged Content Integrity / Impersonation. Re-post as the orchestrator with attribution; cite the in-repo standing-authorization section for the §6a reviewer.
- **`gh pr merge --squash --delete-branch` failed with worktree error**: `fatal: 'main' is already used by worktree at ...`. The `--delete-branch` flag triggers a local prune that touches `main`'s worktree, which can collide with agent worktrees. Drop `--delete-branch`; the merge itself succeeds. Branch can be cleaned later via `git push origin :<branch>` or GitHub UI.
- **ADR-0016 §2 page-pinch suppression rejected under WCAG 1.4.4** (PR #586): the viewport-meta approach (`maximum-scale=1, user-scalable=no`) is forbidden. The narrow fix is CSS `touch-action: none` on specific elements (PR #587). If a similar ADR-vs-UX conflict appears: open an ADR amendment first (or in the same PR if the implementation is the amendment's only consumer), then implement.

## Don'ts

- **Don't** do agent work in the main checkout. Always `isolation: "worktree"`.
- **Don't** send a follow-up question to a still-running agent unless something concrete needs to change. Trust the agent.
- **Don't** spawn multiple agents on overlapping files. Wave structure exists to prevent this.
- **Don't** skip the schema-first phase. ADR-0006 + ADR-0001 §3 are barriers, not suggestions.
- **Don't** push to `main` directly. Even single-line "fix" commits go through a PR.
- **Don't** auto-merge from the orchestrator UNLESS the user has granted explicit merge authority for the rollout (e.g., "merge them when the reviewer comments LGTM, the build checks MUST succeed"). In autonomous mode this is the standard. Without an explicit grant, report ready and wait for the user.
- **Don't** post `@<maintainer>`-authored comments to satisfy a reviewer gate. The auto-mode classifier blocks impersonation. Post as the orchestrator and cite the in-repo standing-authorization section.
- **Don't** do a destructive git operation (force-push, reset --hard, branch -D) outside a worktree without explicit user confirmation per `CLAUDE.md`.
- **Don't** bundle an ADR with the implementation it governs. ADR-0001 §7 — ADR PR merges first, then implementation rebases on top. Plans under `docs/superpowers/plans/` do not satisfy §7; they're task lists, not decision records.
- **Don't** use `perf:` or `style:` commit types. `.commitlintrc.yml` rejects them. `refactor:` for algorithm/perf tweaks; `chore:` for auto-format.
- **Don't** instruct the maintainer to `helm upgrade` from a dev machine in a PR-body runbook. Production paths go through `workflow_dispatch` at the merge SHA.
- **Don't** pin a container image / Helm subchart with a mutable tag. Digest pin (`@sha256:...`) or commit a lock file. A bare tag is not a pin.
- **Don't** gate a GHA matrix step on `matrix.X != ''` unless every row declares `X`. Undefined keys evaluate to `''` silently and the step becomes permanent dead code.
- **Don't** pad a PR with tests to clear a reviewer's "missing tests" finding when doing so pushes past the 400-line target *without justification*. ADR-0001 §6a rule 6 (as amended 2026-05-25): splitting is the default, but a coherent workstream can ship past the target with the body citing the soft-target override.
- **Don't** open a PR that depends on an ADR not yet merged on `main`. Either wait, or cherry-pick the ADR file onto your branch and label the dependency in the PR body.
