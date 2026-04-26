# ADR-0001: Parallel-Agent Development Workflow

## Status

Accepted

## Context

Bliss is a single-maintainer sandbox project where one of the explicit goals is
to experiment with running multiple AI coding agents in parallel — simulating a
team of ~10 contributors. The same coordination problems that hurt human teams
(file collisions, contract drift, redoing each other's work, breaking sibling
modules silently) hurt agent fleets faster, because agents act in seconds and
cannot pick up tribal knowledge from a hallway conversation.

`MANIFESTO.md` already mandates the antidotes: bounded contexts with no
cross-imports, schema-first APIs, hexagonal architecture, architecture tests,
independent module builds, small PRs, conventional commits, ADRs for
non-trivial decisions. None of those rules need to change. What is missing is a
written description of *how a fleet of agents picks up work, isolates from each
other, lands changes, and resolves conflicts*. Without that, the manifesto's
discipline is not actually enforceable on a parallel workflow.

This ADR defines that workflow. It is a prerequisite to ADR-0002 (frontend
stack) and ADR-0003 (cross-language API contract) because both of those
decisions are reviewed and merged under the rules established here.

## Decision

### 1. Unit of work: one bounded context, one workstream, one agent

An agent is dispatched against a single workstream, scoped to a single bounded
context (`grid/`, `frontend/`, `puzzles/`, `accounts/`, `payments/`, …) and a
single architectural layer within it (`domain/`, `application/`,
`infrastructure/`, `api/`).

Cross-context work is forbidden inside a single workstream. If a workstream
needs another context to change, it stops, surfaces the dependency, and a
separate workstream is opened against that context. Agents communicate
*through schemas and merged code*, never by editing each other's files.

### 2. Branch and worktree isolation

- One workstream → one branch → one git worktree.
- Branch naming: `claude/<context>-<short-slug>-<id>`
  (e.g. `claude/grid-application-scoring-aB3xZ`,
  `claude/frontend-grid-canvas-K7p2y`).
- Agents work in isolated worktrees so two concurrent workstreams cannot share
  an index, a dirty file, or a half-staged change.
- Long-lived branches are not allowed. A workstream lives until its PR merges
  or is abandoned; then the worktree and branch are deleted.

### 3. The contract is the coordination layer

Agents do not coordinate by reading each other's code. They coordinate by
reading and writing **schemas**:

- HTTP APIs: OpenAPI in `<context>/api/openapi.yaml`.
- Async/event APIs: AsyncAPI in `<context>/api/asyncapi.yaml`.
- Domain events crossing contexts: declared in `<source-context>/api/events/`.

A workstream that needs a new endpoint, field, or event opens a **schema PR
first**, in isolation, with no implementation. Once that PR is merged,
producer and consumer workstreams proceed in parallel against the agreed
schema. CI fails any PR whose generated types diverge from the committed
schema.

### 4. PR rules (refinement of MANIFESTO.md)

- One workstream = one PR. No bundling.
- Hard cap: 400 lines of diff, generated code excluded. Above the cap, the PR
  is split. Schema regeneration is excluded; logic is not.

  > **Update (2026-04-26): The cap excludes blank lines in addition to
  > generated code. The diff metric GitHub displays is line-by-line
  > including blanks, but the cap's intent is reviewability — blank
  > lines structure readability without adding review burden. Motivating
  > case: PR #50 (Helm chart skeleton) shipped 13 well-organized files
  > totaling 409 GitHub-counted additions, 394 of which were non-blank.
  > The original text is preserved for historical context.**
- Conventional commits with the bounded-context as scope:
  `feat(grid-application): …`, `fix(frontend-grid): …`,
  `chore(api-grid): regenerate openapi types`.
- The PR description must name (a) the workstream, (b) the bounded context and
  layer touched, (c) any schema changes shipped first.
- No PR may modify files outside its declared workstream's scope. CI enforces
  this with a path-allowlist check derived from the PR body.

### 5. Architecture tests are non-negotiable gates

Each context has architecture tests (ArchUnit on JVM, dependency-cruiser or
eslint-plugin-boundaries on TS). They run on every PR, in CI, and fail on:

- domain importing infrastructure, application, or api;
- application importing infrastructure;
- any context importing another context's domain or application;
- vendor SDK imports inside `domain/` or `application/`.

These tests are the safety net that lets agents act fast without leaking
boundaries. They are required, not advisory.

### 6. Merge order is serialized at the trunk

Agents work in parallel. Merges are serialized:

- `main` is the only long-lived branch.
- PRs rebase on `main` immediately before merge. Merge commits are forbidden
  on `main`; **rebase-merge is the default, squash-merge is acceptable**
  for PRs whose commit history would be noise (WIP, fixup, tooling churn).
  Both keep the history linear; rebase preserves the per-commit
  conventional-commit scoping (especially the §6a fix-cycle commits)
  which is more useful for `git log` archaeology than a single squashed
  message.
- The maintainer (human) is the sole arbiter of merge order. Agents do not
  self-merge, even when CI is green.
- If two PRs touch the same schema file, the second one rebases and re-runs
  CI. No merge queue is needed at this scale; serial human review is the
  queue.

### 6a. One review-and-fix cycle before human merge

After CI passes on a workstream PR, exactly one automated review-and-fix
round runs before the human maintainer reviews and merges:

1. A *different* agent than the implementer performs the review. Same-agent
   self-review is forbidden — it has near-zero value.
2. **In scope** for the reviewer: contract adherence (does the implementation
   match the merged schema?), manifesto adherence (domain purity, layer
   boundaries, mocks only at boundaries, test behavior not structure, no
   vendor SDK in domain), test coverage of behavior, obvious bugs.
3. **Out of scope:** formatting (the formatter is the authority), style
   preference, unrelated improvements, scope creep.
4. The implementer addresses each comment with exactly one of: a fix, a
   justified rebuttal in-thread, or `out of scope, see follow-up workstream
   <id>`.
5. The cycle stops there. Further iteration is the human maintainer's job at
   merge time. No agent ping-pong, no second review pass, no second fix pass.
6. If a fix would push the PR over the 400-line cap (generated code
   excluded), the PR is **split**, not padded.
7. Schema PRs receive the same one-pass review, focused on contract design
   rather than implementation correctness.
8. The review-and-fix cycle is required, not best-effort: if no reviewer
   agent is available, the PR waits. The human maintainer reviews and
   merges (per section 6) only after this cycle has run.

### 7. ADRs precede non-trivial implementation

Any decision that is not a pure local refactor — a new dependency, a new
bounded context, a contract change spanning contexts, a build-system change,
a deployment-target change — requires an ADR merged *before* the
implementation PR. This rule already exists in `MANIFESTO.md`; this ADR makes
it explicit that agents are bound by it without exception, because agents
cannot "decide later" the way a human team can.

ADR PRs are themselves workstreams. They follow every rule above except the
400-line cap (which they will not approach).

### 8. What agents may not do without explicit human approval

- Push to `main` directly.
- Force-push to a shared branch.
- Skip CI hooks (`--no-verify`, `--no-gpg-sign`).
- Modify `CLAUDE.md` or `MANIFESTO.md`.
- Add or remove a top-level bounded context.
- Add a new runtime language to the repo (Kotlin and TypeScript are in scope;
  anything else needs an ADR).
- Introduce a paid third-party service.
- Run destructive git operations on shared history.

These are the same constraints a careful human contributor would respect; they
are listed here so an agent reading this file cold knows the perimeter.

### 9. Observability of the fleet

Each PR's title, body, and conventional-commit scope must make it possible to
reconstruct, from `git log` alone, which workstream produced which change.
This is the audit trail that replaces the "who was on call" institutional
memory humans rely on. The repo is the source of truth for who did what, and
the `claude/<context>-<slug>-<id>` branch convention is what makes that
queryable.

## Consequences

### Easier

- Parallel agent work becomes tractable: schema-first contracts mean two
  agents can build producer and consumer in parallel without reading each
  other's code.
- Onboarding (human or agent) is reduced to "read `MANIFESTO.md`, read the
  ADRs, pick a workstream." No tribal knowledge.
- Rollback is per-workstream by design: squash-merge (when chosen)
  collapses a PR to one revert target; rebase-merge keeps commits
  individual, so rollback requires `git revert A..B`. Both keep rollback
  scoped to a single workstream.
- The 400-line cap and one-context rule make every PR reviewable in one
  sitting, which is what makes a single human maintainer a viable bottleneck
  even at 10 concurrent agents.
- The one review-and-fix cycle (§6a) catches most surface-level issues before
  the human maintainer opens the PR, shifting the human role from "first
  reviewer" to "final reviewer." Time spent per PR drops accordingly.

### Harder

- Workstreams that genuinely need cross-context coordination cost more — they
  must be split into a schema PR plus N implementation PRs in dependency
  order. This is the price of the discipline; the alternative (multi-context
  PRs) does not survive a parallel-agent fleet.
- The maintainer becomes the merge-order arbiter and the rebase referee.
  At this scale that is desirable. At larger scale a merge queue (Mergify,
  GitHub merge queue) replaces the human, but that is out of scope for this
  ADR.
- Schemas-first means every API change is a two-step dance (schema PR, then
  implementation PR). This feels heavy for a one-line field addition. It is
  intentional: in a parallel fleet, the second step is what unlocks
  concurrency on either side of the contract.
- Doubled agent cost per workstream: every PR is implemented by one agent and
  reviewed by another. Acceptable in a sandbox; revisit if budget pressure
  appears.

### Different

- "Code review" shifts toward "schema review." Most architectural mistakes are
  caught in the schema PR, before any implementation exists. Implementation
  PRs become primarily a check that the implementation matches the agreed
  contract.
- Branch hygiene becomes a first-class deliverable. Stale `claude/...`
  branches are deleted aggressively after merge or abandonment. The branch
  list is the queue of in-flight workstreams.
- Review threads on PRs become a recorded audit trail of "what almost went
  wrong" — useful for refining agent prompts and for spotting recurring
  failure modes across the fleet.

## Notes

This ADR will be revisited if any of the following occur:

- Three or more documented incidents are caused by parallel-agent collisions
  that the rules here did not prevent.
- The maintainer becomes the bottleneck for merge order at a degree that
  hurts throughput more than the parallelism gains.
- A bounded context grows large enough that "one workstream = one context"
  stops being a meaningful unit of work; sub-contexts may be needed.

Per `MANIFESTO.md`, principles with three or more documented exceptions are
reviewed for revision. The same applies here.
