# WordSparrow

A *mots fléchés* (French crossword variant) puzzle game for web, tablet,
and mobile, with future Discord-Activity support. Brand identity is
recorded in [ADR-0005](./docs/adr/0005-brand-identity.md); "Bliss" is
the working codename used throughout the repo.

Live: <https://bliss-cb4.pages.dev>

## Status

Sandbox / pre-alpha. Daily puzzles generate and play end-to-end; the
multiplayer game context and player identity are in active development.

## Architecture

Bounded contexts, each hexagonally layered
(`domain/` → `application/` → `infrastructure/` → `api/`):

- **`grid/`** — Kotlin/JVM. Puzzle generation, validation, daily
  pre-generation worker ([ADR-0042](./docs/adr/0042-daily-puzzle-pre-generation-worker.md)).
- **`game/`** — Kotlin/JVM. Multiplayer lobbies and realtime play over
  REST + WebSocket ([ADR-0018](./docs/adr/0018-game-bounded-context-and-realtime.md)).
- **`identity/`** — Kotlin/JVM. Player OIDC and session tokens
  ([ADR-0044](./docs/adr/0044-identity-bounded-context-for-player-oidc.md)).
- **`frontend/`** — Vite + React 19 + TypeScript. Player UI, deployed
  as a static bundle to Cloudflare Pages ([ADR-0002](./docs/adr/0002-frontend-stack.md)).

Cross-context communication is schema-first: OpenAPI for HTTP, AsyncAPI
for WebSocket ([ADR-0003](./docs/adr/0003-cross-language-api-contract.md),
[ADR-0019](./docs/adr/0019-asyncapi-2.6-not-3.x.md)). Generated types
are checked in and gated by drift CI.

The engineering rules — bounded contexts, hexagonal layering,
schema-first APIs, parallel-agent workflow — are in
[`CLAUDE.md`](./CLAUDE.md) (binding rules) and
[`MANIFESTO.md`](./MANIFESTO.md) (rationale). Every non-trivial decision
is recorded in [`docs/adr/`](./docs/adr/).

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

Deployment topology is in [`docs/deploy.md`](./docs/deploy.md).

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for branch naming, commit
conventions, DCO sign-off, and local hook setup.

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
