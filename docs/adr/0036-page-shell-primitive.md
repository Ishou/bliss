# ADR-0036: Page shell as a layout primitive

## Status

Accepted

## Context

> **ADR-0001 §7 deviation note:** This ADR is merged in the same PR as
> the primitive implementation. The design direction (two siblings vs.
> a `variant` prop, the `<main>` flex contracts, the overflow-resilience
> invariant, the three-PR rollout) was established in
> `docs/superpowers/specs/2026-05-10-page-shell-primitive-design.md`
> prior to any implementation commit and approved by the user before
> the plan was written. The ADR is the first commit of the PR and
> reviewers see it before any code. Documented one-off matching the
> precedent set by ADR-0034.

Before this ADR, every route in `frontend/src/ui/routes/` reimplemented
its own page shell inline (`pageStyles` + `mainStyles` + `contentStyles`
plus a local `PageShell` function), copy-pasted across routes with small
undocumented drift. The drift produced a recurring class of bugs:

1. **Skip-link silent no-op:** `AppHeader.tsx`'s skip link targets
   `#main-content`. Routes that delegated `<main>` rendering to a child
   component (`/privacy`, `/confidentialite` via `PrivacyNotice`) had no
   element with that id — the skip link silently no-opped, an a11y
   regression that survived because no test covered it.
2. **`mainStyles` flex contract differed everywhere:** `flex: 1 0 auto`
   (Accueil), `flex: 1 1 0; minHeight: 0` (Grille, Lobby), `flex: 1 1
   auto` (Mentions-légales). Each variant was motivated by a real bug;
   none was documented as a contract.
3. **`maxWidth: '720px'` was repeated six times** across routes,
   `AppHeader`, `Footer`, and `PrivacyNotice`, with no shared token.
4. **Overflow-resilience rules** (`minmax(0, 1fr)` on grid tracks,
   `minWidth: 0` on flex children with intrinsic-size content) were
   inconsistently applied — most recently producing a mobile card
   overflow on `/` (Accueil) at 320–375 px viewports.
5. **Lobby footer overlapped content during WAITING** on mobile. The
   lobby route used the same `flex: 1 1 0; minHeight: 0` `<main>` chrome
   as `/grille` because they share the eventual in-game grid. But during
   `WAITING` the route renders normal flow content (`WaitingRoom`: player
   list + grid-size selector + start button) with no inner `flex: 1`
   absorber; on mobile the content overflowed `<main>`'s allotted box
   and visually intruded into the footer below.

The Accueil mobile overflow was the second tactical fix in the same
region in two months; the lobby footer-overlap surfaced during the
audit. The pattern was clear: extract a primitive or keep chasing the
same class of bug.

## Decision

### 1. Two public components, one private chrome

`frontend/src/ui/components/layout/Page.tsx` exports:

- `<ContentPage>` — content-bound shell. Children scroll past the
  viewport; the footer pins to the viewport bottom on short content via
  `flex: 1 0 auto` on `<main>`. Used by `/`, `/aide`,
  `/mentions-legales`, `/privacy`, `/confidentialite`, **and
  `/lobby/$lobbyId` while `lobby.state === 'WAITING'`**.
- `<ViewportPage>` — viewport-bound shell. `<main>` uses
  `flex: 1 1 0; minHeight: 0` so an inner flex child (the grid panel)
  can absorb leftover height. Used by `/grille`, **and
  `/lobby/$lobbyId` once `lobby.state` flips to `IN_PROGRESS` or
  `COMPLETED`** (the grid panel is the inner absorber).
- A private `<PageChrome>` (not exported from `layout/index.ts`) owns
  the shared `<AppHeader>` + `<main id="main-content" tabIndex={-1}>` +
  `<Footer>` wiring.

The two public components are siblings rather than variants of one
component because their layout contracts diverge sharply; a `variant`
prop would either branch internals heavily or expose a footgun where a
route picks the wrong variant and only catches it on a real device.

### 2. Single source of truth for the content cap

`PAGE_MAX_WIDTH = '720px'` is exported from `Page.tsx` and consumed by
`AppHeader`, `Footer`, `PrivacyNotice`, and the two primitives.

### 3. Skip-link contract is invariant

`<main id="main-content" tabIndex={-1}>` is rendered exclusively by
`<PageChrome>`. Components that previously owned their own `<main>`
(e.g. `PrivacyNotice`) become pure content bodies slotted into a page
shell.

### 4. Documented `<main>` flex contracts

| Variant      | `<main>` style                | Rationale                                                                                  |
| ------------ | ----------------------------- | ------------------------------------------------------------------------------------------ |
| ContentPage  | `flex: 1 0 auto`              | Grow to push the footer to viewport bottom on short content; never shrink below content. |
| ViewportPage | `flex: 1 1 0; minHeight: 0`   | Absorb leftover height; allow inner flex children (grid panel) to shrink to fit.           |

### 5. Variant choice: the `flex: 1` test

Use `<ViewportPage>` only when an immediate child of the page is `flex:
1` (or otherwise designed to absorb leftover viewport height) — typically
a grid panel. Use `<ContentPage>` for everything else. A route whose
top-level child is normal flow content does NOT belong in
`<ViewportPage>`; using it there is the content-overflows-into-footer
bug (`audit finding #5` above, surfaced on `/lobby/$lobbyId` during
`WAITING`).

A route whose layout requirements differ per phase (the lobby is the
only example today) selects the variant at the top level based on the
phase predicate. The lobby route picks `<ContentPage>` while
`lobby.state === 'WAITING'` and `<ViewportPage>` once it transitions to
`IN_PROGRESS` or `COMPLETED`.

### 6. Overflow-resilience invariant

Any grid or flex container inside a page shell MUST use `minmax(0, 1fr)`
on grid tracks and `minWidth: 0` on flex children that contain
intrinsic-size content (inputs, code blocks, long URLs). Enforced
empirically by the regression tests at viewports 320 / 375 / 768 px
(`frontend/e2e/page-shell-overflow.spec.ts`).

### 7. New routes consume one of the two primitives

Inline page shells (re-implementing `pageStyles` / `mainStyles` /
`contentStyles`) are an architecture violation. A new layout variant
requires an ADR amendment, not a one-off inline shell.

## Consequences

**Easier:**
- Adding a new route — one component instead of three style blocks plus
  a local `PageShell`.
- Changing the max-width project-wide — one constant, one place.
- Ensuring the WCAG 2.4.1 skip-link works — it's invariant, not
  per-route opt-in.

**Harder:**
- A genuinely novel layout requires a third primitive plus an ADR
  amendment. This is intentional friction.

**Different:**
- `PrivacyNotice` no longer owns its own `<main>` (after PR 2).
- AppHeader/Footer/PrivacyNotice consume `PAGE_MAX_WIDTH` (after PR 1).

## Alternatives considered

- **Single `<PageShell variant="content"|"viewport">`.** Rejected:
  branches internals, hides contract divergence behind a prop,
  footgun-prone.
- **Status quo + lint rule against duplicated `pageStyles`.** Rejected:
  doesn't fix the skip-link contract or the overflow class of bugs.
- **CSS container queries for the layout.** Rejected: orthogonal to the
  shell-extraction problem; container queries solve component-internal
  responsive layout, not page-level chrome.

## Rollout

Three sequential PRs (each independently revertable and green):

- **PR 1 (this one):** primitive + tests + ADR + AppHeader / Footer /
  PrivacyNotice migration to `PAGE_MAX_WIDTH`. No route migrations.
- **PR 2:** migrate `/mentions-legales`, `/confidentialite`, `/privacy`,
  `/aide` onto `<ContentPage>`. Refactor `PrivacyNotice` to drop its
  own `<main>`.
- **PR 3:** migrate `/`, `/grille`, `/lobby/$lobbyId` onto the
  appropriate primitive. The Accueil mobile-overflow fix (`'1fr'` →
  `'minmax(0, 1fr)'` on the mobile grid track) ships with the `/`
  migration. The lobby route picks the variant **per phase** —
  `<ContentPage>` while `lobby.state === 'WAITING'`, `<ViewportPage>`
  once it transitions — fixing audit finding #5
  (`WaitingRoom` content overlapping the footer on mobile). PR 3 also
  adds a seeded `/lobby/$lobbyId` Playwright probe at WAITING phase to
  gate the regression.
