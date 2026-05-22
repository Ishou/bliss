# WordSparrow

A *mots fléchés* (French crossword variant) puzzle game for web, tablet,
and mobile, with future Discord-Activity support. Brand identity is
recorded in [ADR-0005](./docs/adr/0005-brand-identity.md); "Bliss" is
the working codename used throughout the repo.

Live: <https://bliss-cb4.pages.dev>

## Status

Sandbox / pre-alpha. Daily puzzles generate and play end-to-end; the
multiplayer game context and player identity (OIDC) are in active
development. Operated by a single maintainer with a fleet of AI agents
working in parallel ([ADR-0001](./docs/adr/0001-parallel-agent-development-workflow.md)).

## Application architecture

Bounded contexts, each hexagonally layered
(`domain/` → `application/` → `infrastructure/` → `api/`):

- **`grid/`** — Kotlin/JVM. Puzzle generation, validation, word
  corpus. Includes a daily pre-generation worker
  ([ADR-0042](./docs/adr/0042-daily-puzzle-pre-generation-worker.md))
  and the bitmask-CSP grid generator
  ([ADR-0039](./docs/adr/0039-bitmask-csp-grid-generator.md)).
- **`game/`** — Kotlin/JVM. Multiplayer lobbies and realtime play
  over REST + WebSocket
  ([ADR-0018](./docs/adr/0018-game-bounded-context-and-realtime.md)).
- **`identity/`** — Kotlin/JVM. Player OIDC and session tokens
  ([ADR-0044](./docs/adr/0044-identity-bounded-context-for-player-oidc.md),
  [ADR-0047](./docs/adr/0047-token-endpoint-exchange-threat-model.md)).
- **`frontend/`** — Vite + React 19 + TypeScript + Panda CSS +
  TanStack Router. Player UI, deployed as a static bundle to
  Cloudflare Pages ([ADR-0002](./docs/adr/0002-frontend-stack.md)).

JVM is Kotlin 2.x on JDK 21 with Ktor for HTTP/WS
([ADR-0006](./docs/adr/0006-jvm-http-framework.md)) and Postgres via
CNPG + Flyway. Cross-context imports are forbidden; communication is
schema-first via OpenAPI for HTTP and AsyncAPI 2.6 for WebSocket
([ADR-0003](./docs/adr/0003-cross-language-api-contract.md),
[ADR-0019](./docs/adr/0019-asyncapi-2.6-not-3.x.md)), with cross-context
events flowing over NATS JetStream
([ADR-0049](./docs/adr/0049-nats-jetstream-cross-context-events.md)).
Generated TypeScript types are checked in and gated by drift CI.

## Infrastructure (IaC)

All infrastructure is declarative and version-controlled. Nothing is
clicked in a console.

- **Cloud + DNS** — OpenTofu manages a self-hosted Hetzner k3s cluster
  ([ADR-0009](./docs/adr/0009-self-managed-k8s-deployment.md),
  [ADR-0011](./docs/adr/0011-opentofu-for-k8s-subtree.md)), Cloudflare
  DNS records, and the Cloudflare Pages project for the frontend
  ([ADR-0004](./docs/adr/0004-hello-world-deployment.md)). Roots in
  [`terraform/`](./terraform/) (Cloudflare) and
  [`terraform/k8s/`](./terraform/k8s/) (provider-agnostic cluster
  module). State is remote
  ([ADR-0010](./docs/adr/0010-terraform-remote-state-hetzner.md));
  versions are pinned via `versions.tf` and `.terraform.lock.hcl`.
- **Cluster apps** — every in-cluster app ships as a Helm chart under
  [`infra/`](./infra/):
  [`infra/platform/`](./infra/platform/) (ingress-nginx, cert-manager,
  ClusterIssuers), [`infra/observability/`](./infra/observability/)
  (SigNoz + alerts + oauth2-proxy),
  [`infra/nats/`](./infra/nats/) (JetStream streams bootstrapped via
  in-cluster Job), [`infra/matomo/`](./infra/matomo/) (RGPD-compliant
  product analytics, [ADR-0025](./docs/adr/0025-product-analytics-matomo-rgpd.md)).
  App charts and Postgres CNPG clusters live alongside each bounded
  context.
- **Configure-in-cluster, not push-from-CI** — when an app needs config
  bootstrapped (alert rules, JetStream streams, feature-flag seeds), it
  ships as a Helm `post-install,post-upgrade` Job inside the chart
  rather than `kubectl port-forward` from a GitHub Action.
- **Deploy pipelines** — frontend via
  [`.github/workflows/deploy-frontend.yml`](./.github/workflows/deploy-frontend.yml)
  to Cloudflare Pages; APIs + workers via
  [`.github/workflows/deploy-api-k8s.yml`](./.github/workflows/deploy-api-k8s.yml)
  using `helm upgrade --install`. Container images are pinned by digest.

Operational guides: [`docs/local-development.md`](./docs/local-development.md),
[`docs/deploy.md`](./docs/deploy.md), [`docs/secrets.md`](./docs/secrets.md).

## Observability & alerting

OpenTelemetry from day 1, both ends of the stack:

- **Frontend traces** ship to a public OTLP ingest fronted by ingress
  ([ADR-0033](./docs/adr/0033-frontend-otel-public-ingest.md)).
- **Backend telemetry** lands in **SigNoz on ClickHouse**
  ([ADR-0027](./docs/adr/0027-observability-backend-signoz.md),
  [ADR-0041](./docs/adr/0041-clickhouse-keeper-migration.md)), with a
  dedicated worker topology to isolate the observability data plane
  ([ADR-0040](./docs/adr/0040-observability-dedicated-worker-topology.md)).
  Cluster + node metrics flow via the k8s infra collector
  ([ADR-0038](./docs/adr/0038-k8s-infra-pod-node-metrics.md)).
- **Logs are structured JSON** with correlation IDs. No `println`, no
  `console.log`, no string concatenation in log messages.
- **Alerts target symptoms, not causes** — API 5xx rate, frontend error
  rate, daily-puzzle staleness. Alert rules are markdown files in
  [`infra/observability/alerts/`](./infra/observability/alerts/) and
  applied via an in-cluster Helm Job; routing is Gmail SMTP
  ([ADR-0032](./docs/adr/0032-symptom-alerting-api-5xx-via-gmail-smtp.md)).
  The admin UI is gated by oauth2-proxy
  ([ADR-0030](./docs/adr/0030-oauth2-proxy-session-cookie.md)).

## Local AI pipeline (clue generation)

French crossword clues need a French model that respects domain rules
(no stem leak, right register, exact length, valid morphology).
Off-the-shelf APIs don't clear that bar reliably; the project ships a
fully-local pipeline that runs on the maintainer's Mac and produces a
versioned CSV the JVM worker consumes. Pipeline lives in
[`scripts/clue_generation/`](./scripts/clue_generation/).

- **Generator** — mlx-lm LoRA / DPO fine-tunes of
  `c4ai-command-r-08-2024-4bit` on a curated French clue corpus.
  Iterations are config files (`lora_iter*.yaml`) and the most recent
  shipped weights are stitched at run time.
- **Filter** — a CamemBERT cross-encoder trained on accept/reject
  pairs scores candidates; the production pipeline ships the best
  candidate above threshold and drops the rest.
- **Lexical layer** — grammalecte for French morphology and POS
  disambiguation; DBnary as a CC BY-SA lexical data source for
  synonyms used as direct clue candidates
  ([ADR-0023](./docs/adr/0023-dbnary-lexical-data-source.md),
  [ADR-0024](./docs/adr/0024-dbnary-synonym-lemma-as-direct-clue-candidate.md)).
- **Validation** — Python `validate_clue` enforces the structural gates
  before scoring (length, no stem leak, lemma vs surface).
- **Ingestion** — the JVM `words-clues-worker`
  ([ADR-0013](./docs/adr/0013-words-clues-worker.md)) consumes the
  shipped CSV and propagates clues into the grid corpus.

The pipeline stays local on purpose: licence hygiene (DBnary CC BY-SA),
zero per-call cost, and tight iteration loops where the maintainer can
re-train, re-score, and re-ship between commits. Evaluation logbooks
live in [`docs/eval/`](./docs/eval/).

## Claude Code agent orchestration

The repo is built to be worked on by many Claude Code agents in
parallel, with the maintainer as the human-in-the-loop reviewer and
arbiter ([ADR-0001](./docs/adr/0001-parallel-agent-development-workflow.md)).
The operational rules are in [`CLAUDE.md`](./CLAUDE.md); the
rationale is in [`MANIFESTO.md`](./MANIFESTO.md). Mechanics:

- **One workstream per PR, hard-capped at 400 lines of diff** (excluding
  generated code). Branches follow `<type>/<short-description>` and are
  enforced by `branch-name.yml`. Implementer ≠ reviewer (§6a).
- **Skill library** in [`.claude/skills/`](./.claude/skills/) —
  `dispatch` (orchestrator playbook), `reviewer` (§6a reviewer agent),
  `clue-ai`, `jvm-backend`, `frontend`, `schemas`. Skills load via the
  Claude Code Skill tool and encode repo conventions so each agent
  starts with the same context.
- **Worktree isolation** — agents run in `.claude/worktrees/agent-<id>/`
  via the `Agent` tool with `isolation: "worktree"`, so parallel work
  never collides on the working tree.
- **Wave-based rollouts** — large features (multiplayer, custom
  mobile keyboard) are decomposed into a plan under
  `docs/superpowers/plans/`, then dispatched in waves of disjoint PRs.
  The dispatcher orchestrates implementer + reviewer + fixer loops.
- **Autonomous cron mode** (`/orchestrate`) — a 2-minute cron tick
  picks up the plan, dispatches the next phase, runs the auto-fixer
  loop, and merges PRs when CI is green and the §6a reviewer LGTMs.
  Maintainer remains the escalation backstop via the log file.
- **CI gates that keep the fleet honest** — Spotless, Konsist
  architecture tests, `openapi-lint`, `openapi-typescript-drift`,
  `helm-lint`, CodeQL, dependency-review, gitleaks, DCO sign-off,
  conventional commits via `commitlint`, and `claude-code-review` for
  the §6a review/fix cycle. No `--no-verify`, no force-push to shared
  branches.

## Getting started

Local development runs against a k3d cluster that mirrors the prod k3s
topology. See [`docs/local-development.md`](./docs/local-development.md)
for the full walkthrough; the short version:

```sh
make cluster-up         # create k3d cluster (idempotent)
make cluster-bootstrap  # ingress-nginx, cert-manager, CNPG
make deploy-local       # build images, helm install
make dev                # API hot reload + Vite HMR
```

JVM build:

```sh
./gradlew build --parallel --build-cache   # what CI runs
./gradlew spotlessApply                    # fix formatting in place
```

Frontend (from `frontend/`):

```sh
pnpm dev          # Vite + Panda codegen
pnpm test         # vitest
pnpm e2e          # Playwright
pnpm a11y         # axe-core via Playwright (WCAG AA baseline)
pnpm api:check    # regenerate OpenAPI types; fails on drift
```

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for branch naming, commit
conventions, DCO sign-off, and local hook setup. Every non-trivial
change starts with an ADR in [`docs/adr/`](./docs/adr/).

## License

[**FSL-1.1-MIT**](./LICENSE) — Functional Source License 1.1, MIT Future
License.

In plain English:

- **Free for any non-competing use** — personal, internal-business,
  educational, research, professional services around the Software.
- **Commercial competition is restricted** — you may not host or sell a
  product or service that substitutes for, or substantially duplicates,
  WordSparrow.
- **Becomes MIT after two years** — every release auto-converts to a
  full MIT license on the second anniversary of its publication. The
  Software is genuinely open in the long run; the restriction applies
  only to the current frontier.

The full text and edge cases are in [`LICENSE`](./LICENSE). For
commercial-use licensing inquiries that fall outside the Permitted
Purpose, contact ISHO IT EURL.
