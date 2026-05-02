---
name: dispatch
description: Orchestrate parallel-agent work for the Bliss multi-PR rollouts (e.g. the multiplayer "game" feature). Use when the user asks you to dispatch agents in waves, fix CI on an open PR, relaunch a fixer/reviewer loop, or pick up where the previous dispatcher left off. Encodes the conventions, prompt templates, CI gates, common failure modes, and wave-dependency map for this repo.
---

# Bliss dispatcher playbook

This skill is for the **orchestrator** role: a Claude session that doesn't write code itself but dispatches sub-agents (via the `Agent` tool with `isolation: "worktree"`) to do parallel multi-PR work. Refresh your memory from this file at the start of every session that involves opening, fixing, or shepherding more than one PR.

## When this skill applies

- The user describes a feature spanning many files, languages, and bounded contexts (e.g. "lobbies + multiplayer game", "new bounded context").
- The user references a plan file at `/root/.claude/plans/<name>.md` and expects you to follow it.
- The user mentions "waves", "batch work", "parallel agents", "fixer loop", or asks you to "relaunch a reviewer".
- An in-flight PR is failing CI and the user asks you to fix it (or the auto-fix loop hit its cap).

If the task is a single straightforward edit, **do not invoke this skill** — just edit the file. This is for orchestration only.

## Anchor documents

Read these before doing anything substantial. They override anything in this skill.

- `CLAUDE.md` — project-wide engineering rules (binding for every session).
- `MANIFESTO.md` — rationale behind those rules.
- `docs/adr/0001-parallel-agent-development-workflow.md` — **the single most important ADR** for dispatchers. §4 sets the 400-line PR cap, §6 sets the implementer-≠-reviewer pattern, §6a sets the max 5 review passes, §7 sets the "ADR-before-implementation" gate.
- `docs/adr/0003-cross-language-api-contract.md` — schema-first; OpenAPI/AsyncAPI before code; UUID v7 / ISO-8601 / camelCase / RFC 7807 / explicit `required` and `nullable`.
- `docs/adr/0006-jvm-http-framework.md` — Ktor + Kotlin; SSE for v1, WebSocket deferred until multiplayer (the multiplayer rollout resolves that defer).
- `docs/adr/0009-self-managed-k8s-deployment.md` — Helm chart layout + CNPG.
- `docs/adr/0018-game-bounded-context-and-realtime.md` — multiplayer architecture.
- The current plan file (`/root/.claude/plans/<adjective-adjective-noun>.md`) — the wave-by-wave rollout map.

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
7. **CI auto-fix loop** (paste-ready snippet — see below).
8. **Report-back contract**: max ~250 words, branch + PR URL, line counts, test/lint/build outputs, decisions beyond the prompt, blockers.

### Branch + commit conventions

- **Branches**: `<type>/<short-description>` where `<type>` is one of `feat|fix|chore|refactor|test|docs`. Enforced by the `branch-name` CI check. The Claude bot uses the `chore/claude-` prefix (e.g. `chore/claude-game-domain-scoring`) so its branches satisfy the conventional-type requirement per CLAUDE.md. (Historical note: ADR-0001 §2 used `claude/<context>-<slug>-<id>`; CLAUDE.md is the current authority.)
- **Commit messages**: Conventional Commits, single scope (no commas — commitlint rejects `fix(grid-api,grid-worker):`). Use `fix(grid):` if the change spans multiple submodules of the same context.
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
| `regen-and-diff` fails | OpenAPI changed but generated TS types not regenerated | `cd frontend && pnpm api:generate`; commit the diff. The generated file is excluded from the line cap per ADR-0001 §4. |
| `spectral` fails | New AsyncAPI/OpenAPI lint violation | Fix the schema. `npx -y @stoplight/spectral-cli@latest lint <file> --ruleset=@asyncapi/spectral-ruleset` or the OpenAPI ruleset. |
| ADR number collision | Two PRs picked the next ADR number simultaneously | One PR keeps the original number; the other renumbers to the next free slot. Update title, all in-document refs, and references in companion specs. |
| `claude-review` flags "ADR-XXXX missing from this branch" | The schema PR references an ADR that lives only on a sibling PR | Cherry-pick the ADR file onto this branch (`git checkout origin/<sibling-branch> -- docs/adr/XXXX-*.md`). When sibling lands first, conflict-merge is a clean overlap. |
| Cross-context `$ref` flagged | AsyncAPI references `grid/api/openapi.yaml#/...` | Inline the schema with a "mirrors grid/api/openapi.yaml; ADR-0001 §1 forbids cross-context $ref" comment. Update both files together when the wire format evolves. |
| `nullable: true` field absent from `required` | ADR-0003 §6: absence ≠ null | Add the field to `required`. The wire always sends the field; `null` is the explicit blank value. |

## Frontend conventions

- Stack: Vite + React 19 + TanStack Router + Panda CSS + Vitest. ADR-0002 §4: inputs are uncontrolled.
- Hexagonal layers: `src/domain/`, `src/application/`, `src/infrastructure/`, `src/ui/`. eslint-plugin-boundaries enforces the layering — don't violate it.
- Generated OpenAPI types live at `src/infrastructure/api/<context>/types.ts`. CI's `regen-and-diff` job rebuilds them and fails if stale.
- Tests use Vitest + Testing Library. `pnpm test --run tests/<file>.test.tsx` for a single file.
- A pre-existing failure in `tests/http-puzzle-repository.test.ts` (`width:5` vs the 10×10 fixture) is unrelated to most PRs — note it in PR bodies as known-pre-existing-on-main. Don't try to fix it from an unrelated PR.

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
Plan: `/root/.claude/plans/<plan>.md`. Read §<sections> first.
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
- ADR-0001 §4: 400-line cap on meaningful changes (generated code excluded).
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

## Worktree hygiene

Agents that finish leave their worktree at `/home/user/bliss/.claude/worktrees/agent-<id>/` with the branch checked out. To free a branch (so you can `git checkout` it from the main repo):

```
git worktree remove /home/user/bliss/.claude/worktrees/agent-<id> -f -f
```

The double `-f -f` overrides the agent-process lock. Worktrees with no commits auto-clean on agent exit; ones with commits persist until manually removed.

## Failure-recovery patterns I've actually used

- **Two ADRs both number 0018** (PR #121 + PR #122 collision): renumbered the second to 0019, updated all in-doc references, force-pushed.
- **Auto-reviewer flagged "ADR-0018 missing from branch"** even though it was on main (PR #121 had merged): cherry-picked the ADR file onto the dependent branch so the review on the standalone branch resolves cleanly.
- **PR #126 broke `grid/api` Docker build** because `settings.gradle.kts` got a new `:game:domain` include and the `grid/api/Dockerfile` only `COPY`d grid modules: added `COPY game/domain/build.gradle.kts game/domain/` mirroring the existing `:grid:worker` workaround.
- **Em-dash in `@Test` names** crashed `compileTestKotlin` under POSIX-locale CI: renamed to ASCII; rejected `-Dfile.encoding=UTF-8` because the JVM reads `sun.jnu.encoding` before `-D` flags apply.
- **`commitlint` rejected `fix(grid-api,grid-worker):`**: amended to `fix(grid):` (single scope per spec).
- **Stale stash in `git stash list`** from a prior dispatcher session: drop after confirming the PR that supersedes it has been opened.

## Don'ts

- **Don't** do agent work in the main checkout. Always `isolation: "worktree"`.
- **Don't** send a follow-up question to a still-running agent unless something concrete needs to change. Trust the agent.
- **Don't** spawn multiple agents on overlapping files. Wave structure exists to prevent this.
- **Don't** skip the schema-first phase. ADR-0006 + ADR-0001 §3 are barriers, not suggestions.
- **Don't** push to `main` directly. Even single-line "fix" commits go through a PR.
- **Don't** auto-merge from the orchestrator. The user merges. You report ready.
- **Don't** do a destructive git operation (force-push, reset --hard, branch -D) outside a worktree without explicit user confirmation per `CLAUDE.md`.
