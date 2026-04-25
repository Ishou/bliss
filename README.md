# Bliss

A *mots fléchés* (French crossword variant) puzzle game, designed for web,
tablet, and mobile, with future Discord-Activity support.

Live: <https://bliss-cb4.pages.dev>

## Status

Sandbox / pre-alpha. Hello-world deployed; first interactive feature
not yet shipped.

## Architecture

Two language stacks across bounded contexts:

- **`grid/`** — Kotlin/JVM domain (puzzle generation, validation).
- **`frontend/`** — Vite + React 19 + TypeScript (player UI; deployed as
  a static bundle to Cloudflare Pages).

Communication between them is schema-first via OpenAPI, per
[ADR-0003](./docs/adr/0003-cross-language-api-contract.md).

The engineering rules — bounded contexts, hexagonal layering, schema-first
APIs, parallel-agent workflow — are in [`CLAUDE.md`](./CLAUDE.md) (binding
rules) and [`MANIFESTO.md`](./MANIFESTO.md) (rationale). Every non-trivial
decision is recorded in [`docs/adr/`](./docs/adr/).

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
  Bliss.
- **Becomes MIT after two years** — every release auto-converts to a
  full MIT license on the second anniversary of its publication. The
  Software is genuinely open in the long run; the restriction applies
  only to the current frontier.

The full text and edge cases are in [`LICENSE`](./LICENSE). For
commercial-use licensing inquiries that fall outside the Permitted
Purpose, contact ISHO IT EURL.
