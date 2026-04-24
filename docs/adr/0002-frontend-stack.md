# ADR-0002: Frontend Stack

## Status

Accepted

## Context

Bliss needs a user-facing application for playing *mots fléchés* (French
crossword variant) across web, tablet, and mobile, with a longer-term option
to ship as a Discord Activity. The product target is a polished, monetizable
puzzle game; the touch-input experience must feel near-instant — input lag
disqualifies a stack regardless of how clean the visual output is.

The Kotlin backend stays (per the project goals: AI-driven puzzle generation,
JVM ecosystem, learning value). The frontend therefore has to integrate with a
JVM backend across a language boundary, under the manifesto's schema-first
discipline.

The selection space considered:

- **Meta-framework:** Next.js (App Router) vs. Vite + TanStack Router vs.
  SolidStart vs. SvelteKit vs. React Native / Expo vs. Flutter vs. Compose
  Multiplatform Web.
- **Component library:** shadcn/ui, Ark UI, Mantine, HeroUI, Chakra v3, Joy
  UI, Radix Themes, Park UI, Tailwind UI / Catalyst, Headless UI.
- **Styling:** Tailwind v4, Panda CSS, vanilla-extract, plain CSS modules.
- **Grid rendering:** DOM with memoized cells vs. `<canvas>` with custom hit
  testing.

The decision below picks one path for v1 and explicitly lists what is *not*
being decided here, to keep this ADR within the manifesto's "small,
single-concern" convention.

## Decision

### 1. Meta-framework: Vite + React 19 + TypeScript + TanStack Router

Vite over Next.js because Bliss is a game, not a content site. SSR, RSC,
streaming, and edge rendering are features Next.js charges ongoing complexity
for; none of them help a crossword grid feel snappier on a phone. Vite's
SPA-first model gives the smallest cold-start and the simplest mental model.

TanStack Router over React Router for type-safe routing (required by the
manifesto's "no untyped boundaries" stance) and first-class search-param
state — directly useful for shareable puzzle URLs (relevant to monetization
via shareable scoreboards).

React over Solid/Svelte despite Solid's measured performance edge: the
ecosystem depth (libraries, hiring, AI-tooling familiarity, parallel-agent
work) outweighs the per-keystroke reconciliation cost for a 225-cell grid,
provided the grid is rendered with discipline (see §4 below).

### 2. Component library: Ark UI (headless)

Ark UI over shadcn/ui because the visual aesthetic of shadcn is not the
target. Ark is purely headless — accessibility, focus management, and state
machines for every common pattern (combobox, dialog, menu, popover, slider,
toast) — with the visual layer entirely owned by Bliss.

Rationale for "headless rather than themed":

- Zero animation runtime imposed on the input path. HeroUI / Chakra / MUI
  ship Framer Motion or equivalent on hover/press; on low-end Android this is
  measurable jank.
- Bundle stays small. Only the primitives actually used end up in the
  bundle.
- Long-term polish is unconstrained by a vendor's design language.

Ark UI is built by the Chakra team and pairs natively with Panda CSS (§3).

### 3. Styling: Panda CSS

Panda CSS over Tailwind v4 for two specific reasons:

- **Typed design tokens.** Panda generates a typed token system from a single
  config (colors, spacing, radii, shadows, motion). Manifesto's "no untyped
  boundaries" applies to design as well as code.
- **Zero runtime, atomic CSS at build time.** Same input-latency profile as
  Tailwind, with the addition of `cva`-style variants and recipes built in.

Tailwind would have been a defensible choice; Panda is the closer fit with
Ark UI (same authors, intended pairing) and gives a stronger long-term
foundation for a designed product.

### 4. Grid rendering: DOM with memoized cells (canvas deferred)

The crossword grid renders as DOM nodes (one element per cell), with:

- Each cell wrapped in `React.memo`, dependent only on its own cell state.
- Uncontrolled inputs (`ref`-based) for keystrokes; the React tree does not
  re-render on every key.
- `touch-action: manipulation` on the grid root to eliminate the iOS 300ms
  tap delay.
- No per-cell entry/exit animations; animations occur only on word
  completion, on the affected cells.
- Accessible by default: each cell is a real input, screen readers and
  hardware keyboards work without extra plumbing.

Canvas rendering is *not* adopted in v1. It is the right escape hatch if
DOM-based input ever measurably fails the responsiveness target on low-end
devices, but adopting it preemptively trades away accessibility, focus
behavior, IME support, and developer ergonomics for a perf budget that may
never be tight. Decision is reversible: if measurements demand it, a future
ADR replaces the rendering strategy.

### 5. Distribution: PWA first, Capacitor when needed

v1 ships as a Progressive Web App:

- Installable on iOS, Android, desktop.
- Offline-capable via a service worker (caches puzzles played and assets).
- Web Push for daily-puzzle notifications where supported.

Native app-store presence is deferred. When monetization requires it (Apple
in-app purchases, Play Store discovery, app-store featured placement),
Capacitor wraps the same web build as a native shell. This preserves a
single codebase across web, iOS, Android. Capacitor is *not* a v1
deliverable; it is the planned escape hatch.

### 6. Discord Activity: kept compatible, not yet implemented

Discord Activities run web content in a sandboxed iframe with a constrained
embed SDK. The Vite + React build is structurally compatible; the
work needed to ship as an Activity is additive (Discord SDK integration,
auth flow, embed-specific layout adjustments), not a fork. No code in v1
should make Activity adoption harder; specifically: no hard dependency on
`window.top`, no CORS assumptions that break in an embedded iframe, no
hard-coded full-screen layouts.

### 7. Bounded context layout

A new top-level bounded context `frontend/` is introduced, mirroring the
manifesto's structure within its own conventions:

```
frontend/
├── src/
│   ├── domain/         (UI-domain types: GridView, CellState, etc.)
│   ├── application/    (use-cases, hooks orchestrating domain + adapters)
│   ├── infrastructure/ (HTTP client, storage, telemetry adapters)
│   └── ui/             (Ark + Panda components, routes, pages)
├── tests/
└── package.json
```

Cross-context isolation is enforced by `eslint-plugin-boundaries` (the
manifesto's "ArchUnit equivalent" for TypeScript). The frontend's
`infrastructure/` layer is the only place allowed to import generated API
clients; UI components consume application hooks, never raw HTTP.

### 8. Out of scope for this ADR

The following are intentionally deferred to keep this decision focused:

- **State management** beyond React's built-ins (Zustand vs. Jotai vs. plain
  React) — decided when the first cross-route shared state appears.
- **Form library** — Ark UI covers most input primitives; a form library is
  added when validation complexity warrants it.
- **Testing stack** — Vitest + Testing Library + Playwright is the likely
  pick, but lives in its own ADR alongside the testing strategy for the
  frontend.
- **Telemetry / error reporting / analytics** — decided alongside the
  observability ADR for the project as a whole.
- **Authentication** — decided when the `accounts/` bounded context is
  introduced.
- **Build/deploy target** (Cloudflare Pages, Vercel, self-hosted, etc.) —
  decided in the deployment ADR.

## Consequences

### Easier

- Touch responsiveness is structurally protected: no animation runtime on the
  input path, uncontrolled inputs, memoized cells, no SSR hydration cost.
- Distribution flexibility is preserved: web, PWA install, Capacitor-wrapped
  native, Discord Activity all reachable from one codebase.
- Visual identity is owned by Bliss, not inherited from a component vendor.
  Long-term polish is uncapped.
- Bundle stays small from day one — directly relevant to mobile retention and
  monetization conversion.
- Headless + token-based styling means design changes do not ripple through
  component internals.

### Harder

- More wiring up front than picking a themed library. Ark + Panda require
  building a small set of styled primitives (Button, Input, Dialog) before
  feature work starts. Mitigation: this is a one-time cost, ~1-2 days, paid
  back for the project lifetime.
- TanStack Router is less ubiquitous than React Router; AI-tooling
  familiarity is good but not perfect. Mitigation: documentation is
  excellent and the API surface is small.
- Two languages, two build systems (Gradle, pnpm). Already accepted under
  ADR-0001 and the manifesto.

### Different

- Performance discipline shifts left. Memoization, uncontrolled inputs, and
  bundle budgets are not optimizations applied later — they are part of the
  initial component contract.
- Design work is decoupled from component-library upgrades. There is no
  `npm update mui` that ever changes how a button looks.
- The frontend gains the same hexagonal layering as JVM contexts, enforced
  by lint rules. Onboarding agents can apply the same mental model to
  `frontend/` as to `grid/`.

## Notes

This ADR is reversible at the meta-framework, component-library, and styling
layers, in increasing order of effort. Replacing Panda with Tailwind is a
mechanical sweep; replacing Ark with another headless library is a
component-by-component refactor; replacing Vite/React/TanStack Router is a
near-rewrite of `frontend/ui/` and `frontend/application/`. Reversibility
costs were considered in the choice; the bet is that Vite + React + Ark +
Panda is stable for the project's foreseeable lifetime.

This ADR is revisited if any of the following occur:

- Measured input latency on a mid-range Android device exceeds 80 ms p95
  on the grid input path after reasonable optimization. Canvas rendering or
  a different framework becomes a candidate.
- Bundle size at v1 release exceeds 200 KB gzipped for the initial
  route. Stack assumptions are revisited.
- Discord Activity or native-store distribution is blocked by a stack
  constraint not anticipated here.
