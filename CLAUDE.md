# Bliss — Project Context for Claude

> Rationale lives in [`MANIFESTO.md`](./MANIFESTO.md). Decisions live in
> [`docs/adr/`](./docs/adr/). Onboarding lives in
> [`CONTRIBUTING.md`](./CONTRIBUTING.md),
> [`docs/local-development.md`](./docs/local-development.md), and
> [`docs/deploy.md`](./docs/deploy.md). **This file is the operational
> map**: where things live, which commands to run, what never to do.
> If something is binding here, follow it; if rationale is missing, the
> ADR or MANIFESTO has it.

## What this repo is

- "Bliss" is the working codename; the product is **WordSparrow**
  (ADR-0005), a French *mots fléchés* puzzle game.
- Live: <https://bliss-cb4.pages.dev>. Status: sandbox / pre-alpha.
- Single-maintainer + a fleet of AI agents working in parallel
  (ADR-0001). Most operational rules below exist because they make
  that fleet tractable.

## Bounded contexts

| Path        | Layers                                      | Stack                                                                          | Schema                                                          |
|-------------|---------------------------------------------|--------------------------------------------------------------------------------|-----------------------------------------------------------------|
| `grid/`     | `domain/` `application/` `infrastructure/` `api/` `worker/` | Kotlin 2.3.21 + Ktor on JDK 21; Postgres via CNPG + Flyway                     | `grid/api/openapi.yaml`                                         |
| `game/`     | `domain/` `application/` `infrastructure/` `api/`           | Kotlin/JVM + Ktor REST + WebSocket (ADR-0018)                                  | `game/api/openapi.yaml` + `game/api/asyncapi.yaml` (ADR-0019)   |
| `frontend/` | `domain/` `application/` `infrastructure/` `ui/`            | Vite + React 19 + TS + Panda CSS + Ark UI + TanStack Router + OTel (ADR-0002)  | consumes both; types generated from the YAMLs                   |

Hexagonal: `domain/` depends on nothing; `application/` defines ports;
`infrastructure/` implements adapters; `api/` is the HTTP/WS edge. No
vendor SDK imports in `domain/` or `application/`. Cross-context imports
are forbidden — communicate via merged schemas or domain events.

Architecture tests enforce this in CI:
- JVM: **Konsist** (`*ArchitectureTest.kt` under each module's `test/`).
- TS: `eslint-plugin-boundaries` (`frontend/eslint.config.js`).

## Commands

### Local cluster (mirrors prod k3s via k3d — see [`docs/local-development.md`](./docs/local-development.md))

```sh
make cluster-up         # create k3d cluster (idempotent)
make cluster-bootstrap  # install ingress-nginx, cert-manager, CNPG
make deploy-local       # build grid-api, import into k3d, helm install
make dev                # API hot reload + Vite HMR (FORCE=1 kills strays on 7777/7778/5173)
make cluster-status     # kubectl get nodes,pods -A
```

### JVM (`grid/`, `game/`) — from repo root

```sh
./gradlew build --parallel --build-cache      # all contexts, what CI runs
./gradlew :grid:application:test --parallel   # one module
./gradlew spotlessCheck                       # CI gate; spotlessApply to fix in place
```

### Frontend — from `frontend/`

```sh
pnpm dev          # Vite + Panda codegen
pnpm test         # vitest
pnpm e2e          # Playwright
pnpm a11y         # axe-core via Playwright (ADR-0050 a11y baseline)
pnpm typecheck    # tsc -b (panda codegen runs first)
pnpm api:check    # regenerate OpenAPI types and fail on drift — run after schema edits
```

## Schema-first workflow (ADR-0001 §3, ADR-0003)

The schema is the contract; do not hand-edit generated types.

1. Open a **schema-only PR** touching `<ctx>/api/openapi.yaml` (or
   `asyncapi.yaml`) with no implementation. CI gate: `openapi-lint`.
2. After it merges, producer and consumer PRs land in parallel.
3. Frontend regenerates types via `pnpm api:check`; the
   `openapi-typescript-drift` gate fails any PR whose
   `frontend/src/infrastructure/api/{grid,game}/types.ts` is out of sync.

## CI gates (must be green to merge)

Workflows in [`.github/workflows/`](./.github/workflows/):

- `ci` — Gradle build, tests, Spotless, Konsist arch tests.
- `commitlint`, `dco`, `branch-name`, `secret-scan` (gitleaks).
- `openapi-lint`, `openapi-typescript-drift`, `helm-lint`, `api-chart-lint`.
- `codeql`, `dependency-review`.
- `claude-code-review` — automated §6a review/fix cycle on PRs.

Never bypass: no `--no-verify`, no `--no-gpg-sign`, no force-push to
`main` or to another agent's branch.

## Branches, commits, PRs

- **Branch:** `<type>/<short-description>` where `<type>` ∈
  {`feat`, `fix`, `chore`, `refactor`, `test`, `docs`}. Enforced by
  `branch-name.yml`. **This supersedes ADR-0001 §2's
  `claude/<context>-...` form.** Bot exemptions: `dependabot/`,
  `renovate/`, `chore/claude-`.
- **Commit:** conventional, with bounded-context scope (and layer when
  it sharpens the message):
  - `feat(grid-application): score puzzles by interlock density`
  - `fix(frontend-grid): handle empty cell on backspace`
  - `chore(api-game): regenerate openapi types`
  - **WIP commits must use a valid type** — `wip(...)` is rejected by
    commitlint. Use `chore(<scope>): wip ...`.
- **PR:** one workstream per PR. Hard cap **400 lines of diff**,
  excluding generated code and blank lines (ADR-0001 §4, 2026-04-26
  addendum). The body names the workstream, the bounded context and
  layer, and any schemas shipped first. No PR modifies files outside
  its declared scope.
- **DCO:** every commit is signed off (`git commit -s`). Bot-authored
  commits (`*[bot]`) are exempt.

## Deployment

- **Frontend** → Cloudflare Pages (ADR-0004) via
  `.github/workflows/deploy-frontend.yml`.
- **API + worker** → self-managed k3s on Hetzner (ADR-0009) via
  `.github/workflows/deploy-api-k8s.yml`. Helm charts in
  `infra/platform/charts/`. Provisioning is OpenTofu in `terraform/`
  (ADR-0010, ADR-0011).
- **Daily-puzzle pre-generation** is a k8s CronJob (ADR-0042).
  **Words/clues batch worker** is ADR-0013.
- Secrets inventory & recovery: [`docs/secrets.md`](./docs/secrets.md).
  Deploy bindings: [`docs/deploy.md`](./docs/deploy.md).

## Observability

- Structured JSON logs with correlation IDs. **No `println` / no
  `console.log`. No string concatenation in log messages.**
- OpenTelemetry from day 1; the frontend ships traces via a public
  ingest (ADR-0033).
- Backend: **SigNoz on ClickHouse** (ADR-0027, ADR-0041), with a
  dedicated worker topology (ADR-0040).
- Alerts on **symptoms** (API 5xx via Gmail SMTP, ADR-0032), not
  causes.

## Engineering rules with operational bite

These are the principles that change *what an agent does in this repo*.
Full rationale is in MANIFESTO.md.

- **TDD for domain logic.** Failing test first, then implementation,
  then refactor. Don't test trivial getters/delegation. Domain logic
  targets near-100% mutation coverage.
- **Mock only at external boundaries.** Never mock a class you wrote
  — use the real instance or an in-memory implementation.
- **Property-based tests** for serialization, parsing, validation.
- **Small PRs, one workstream.** See the 400-line cap above.
- **ADR before non-trivial change.** A new dependency, a new bounded
  context, a contract change spanning contexts, a build-system or
  deploy-target change — ADR merges first (ADR-0001 §7).
- **Migrations are expand-and-contract**, backward-compatible.
- **Feature flags** deploy dark, release bright; flags carry expiry
  dates.
- **Accessibility is a requirement** (WCAG AA, ADR-0050), not a
  follow-up ticket.
- **Secrets never in code.** Injected at runtime. Git hooks and
  `secret-scan` (gitleaks, listed in CI gates above) prevent accidental
  commits.
- **Auth/authz changes need a threat model.** ADR or PR body must
  include one before the PR is reviewed.
- **Configure-in-cluster, not push-from-CI.** When the work is
  *configure* an app already running in the cluster (apply SigNoz
  alert rules, create a JetStream stream, seed a feature-flag table),
  do it with a Helm `post-install,post-upgrade` Job *inside* the
  chart — mirror `infra/nats/templates/stream-bootstrap-job.yaml`.
  The Service is directly reachable from a Job in the same namespace;
  the app's API key lives as a k8s Secret. CI's role is
  `helm upgrade --install`, not POSTing JSON. **Tell** you're
  about to make this mistake: you're typing `kubectl port-forward`,
  `KUBECONFIG_PROD`, or "oauth2-proxy bypass" into a GitHub Actions
  workflow whose actual job is "send a few HTTP requests to an
  in-cluster app". Stop and use the chart-Job pattern.
- **Three patches on the same path = the shape is wrong.** When the
  same workflow / script / pipeline has needed three local fixes in
  sequence (each unlocking the next failure), the architecture is
  wrong, not the patch. Step back and ask "what would I do designing
  this from scratch today?" — if the answer differs from what you're
  patching, switch.
- **Fetch a known-working example before authoring a payload for a
  complex external API.** When you're about to write JSON / YAML / SQL
  for a system you have never successfully talked to, the canonical
  shape comes from a source of truth — the server's request-validator
  source code, its repo's official example file, or a `GET` of an
  existing resource you create via its UI. **Not** from documentation
  prose or LLM-synthesized schemas. Patching reactively to "field X
  missing" / "field Y has wrong type" error messages costs one fix
  cycle per missing field. The 2026-05-21 SigNoz alerts workstream
  cycled through 4 PRs (auth header → `version: v5` → full v5 shape →
  `notificationSettings`) because the first author synthesized from
  partial docs; a single fetch of `pkg/apiserver/signozapiserver/
  ruler_examples.go` from the SigNoz repo would have given the
  complete shape in one. **Tell** you're about to make this mistake:
  you have a schema-validator error from a remote API, you're about
  to edit one missing field, and you have not yet fetched a known
  good example. Stop. Fetch first.

## Things to never do without explicit approval

- Add or remove a top-level bounded context.
- Add a new runtime language (Kotlin and TypeScript are in scope).
- Introduce a paid third-party service.
- Modify `MANIFESTO.md` or this `CLAUDE.md`.
- Push directly to `main`, force-push a shared branch, or run
  destructive git on shared history.

## How to collaborate with the maintainer

- **Challenge bad ideas.** "This is a bad idea because…" — don't just
  execute. Every recommendation names trade-offs (what else was
  considered, downsides).
- **No sycophancy.** Skip "great question." Be honest, respectful,
  direct.
- **Verify, don't guess.** Read the file. Run the build. Say "I don't
  know" rather than guessing.
- **Read the ADR before non-trivial work.** Recent landmarks: 0001
  (workflow), 0003 (cross-language API), 0009 (k3s deploy), 0018
  (game context), 0050 (a11y), 0039 (bitmask-CSP grid generator),
  0042 (daily pre-gen worker).

## ADR template

```
# ADR-NNNN: Title

## Status
Proposed | Accepted | Superseded by ADR-XXXX

## Context
What is the issue? Why does a decision need to be made?

## Decision
What was decided.

## Consequences
What becomes easier, harder, or different.
```
