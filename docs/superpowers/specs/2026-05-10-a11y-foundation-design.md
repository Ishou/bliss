# A11y foundation — design

**Date:** 2026-05-10
**Branch:** `feat/a11y-foundation`
**Status:** Design approved, plan pending

## 1. Context

The manifesto requires WCAG AA accessibility as a baseline, but the codebase has no
automated a11y gate and no validated screen-reader experience for the mots-fléchés
grid. The grid component already carries decent semantics (`role="grid"`, `role="row"`,
French direction labels on arrow cells, an `aria-live` clue panel) and arrow-key
navigation, but the panel's live region re-announces on every cell move, the cells
do not communicate their slot context, and there is no automated regression net.

This branch establishes the a11y baseline (audit + CI gate) and ships a screen-reader
experience that lets a VoiceOver user actually complete a puzzle.

## 2. Goals & non-goals

### Goals
- WCAG 2.2 AA compliance enforced in CI on a representative set of routes.
- A VoiceOver user on macOS can navigate the grid, hear the active clue with the
  slot pattern, hear cell context on every focus move, and receive validation,
  hint, and multiplayer events without leaving the grid.
- An ADR documents the baseline, the canonical screen reader, the severity policy,
  and the deferred items.

### Non-goals (out of scope this branch)
- iOS VoiceOver and NVDA/JAWS testing.
- Settings UI for a11y preferences (verbose toggles, slot-pattern mute,
  multiplayer mute, re-announce hotkey).
- Cognitive accessibility (simpler copy, dyslexia font).
- Captions or sign-language translation (no audio content today).
- Mobile-specific gesture accessibility.

## 3. Sub-PR breakdown

The branch is an epic. Each sub-PR is ≤400 lines, conventional-commit titled,
DCO-signed, branched off `main` (not chained off the previous PR) so any sub-PR
can land or pause independently.

| # | Title | What it ships |
|---|---|---|
| **PR-A1** | `feat(a11y): wire axe-core into e2e + vitest` | `@axe-core/playwright` runs on the representative routes (see §4); `vitest-axe` matcher available; CI fails on `serious`/`critical`, warns on `moderate`; ADR-00XX added. |
| **PR-A2** | `fix(a11y): resolve baseline axe findings` | Whatever PR-A1 surfaces. May fan out into PR-A2a, PR-A2b. Stops when `serious`/`critical` count is zero. |
| **PR-B1** | `feat(grid): skip link + keyboard polish` | Skip link in `AppHeader`; `useGridNavigation` gains Home/End and Tab/Shift-Tab between words; focus restoration after puzzle reload, route nav, modal close. |
| **PR-B2** | `feat(grid): screen-reader announcements` | New visually-hidden `aria-live` regions decoupled from the visible clue panel. Cell aria-label = position-in-word + letter or "vide". Word-transition announcements with slot pattern. Validation, hint, and multiplayer events announced (default-on, no toggle). |
| **PR-B3** | `chore(a11y): VoiceOver manual pass + tweaks` | Run the VO checklist from the ADR; fix what's wrong; close the epic. |

## 4. Tooling stack (PR-A1)

### Audit libraries
- **`@axe-core/playwright`** — runs axe inside an existing Playwright page. Reuses
  the existing e2e harness; no new browser, no new CI job. New file:
  `frontend/e2e/a11y.spec.ts`.
- **`vitest-axe`** — `expect(html).toHaveNoViolations()` matcher for component
  tests. New helper at `frontend/src/test/a11y.ts` exporting a
  `renderAndAxe(<Component />)` wrapper.

Not added: Lighthouse CI (overlaps axe, slower), pa11y (axe-based, redundant),
`eslint-plugin-jsx-a11y` (false-positive heavy on ark-ui's render-prop pattern;
revisit later).

### Routes scanned in e2e

A representative set of 5–6 routes covering loading, playing, error, lobby, and
end-game states. **Exact route paths and IDs to be confirmed against
`frontend/src/ui/routes/` during plan-writing**; the spec commits to coverage
intent, not specific paths.

Coverage intent:
1. Home / landing.
2. Solo grid — loaded and one cell focused.
3. Solo grid — after a deliberate validation error (error state present in DOM).
4. Multi waiting room.
5. End-game modal open.
6. Generic error route.

Each route loads, axe runs with the WCAG 2.2 AA tagset
(`['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']`), and violations are
filtered by impact.

### CI severity policy

| impact | gate |
|---|---|
| `critical`, `serious` | **fail** the job |
| `moderate` | log, do not fail (tracked in ADR for tightening) |
| `minor` | ignored |

Rationale: starting strict on `serious`+ avoids drift; `moderate` is loose
initially so PR-A1 lands without bundling the fixes (those are PR-A2). The ADR
commits to ratcheting `moderate` to fail within 60 days of PR-A1 merge.

### Disabled rules (justified in ADR)

- `color-contrast` for the active grid cell **during the focus-pulse animation
  only**. The steady-state must pass; the pulse mid-frame would flap. Implemented
  as a per-call `disableRules` with a tight CSS selector, not a global disable.

### Local DX

- `pnpm a11y` script = the e2e a11y spec only, for tight feedback loops.
- No new pre-commit hook (CI is the gate; manifesto's "CI is the only path" rule).

## 5. Announcement architecture (PR-B2)

The existing `CurrentCluePanel` has `role="status" aria-live="polite"`. The panel
re-renders on every focused-cell change, so VoiceOver currently re-announces the
clue on every cell move. The visible panel and the announced channel must be
decoupled.

### Two visually-hidden live regions

- `<AnnouncerLive />` — `aria-live="polite"`, `role="status"`. Fires on word
  transition and on most events.
- `<AnnouncerAlert />` — `aria-live="assertive"`, `role="alert"`. Fires on
  validation errors that interrupt mid-input.

Both live in `frontend/src/ui/components/a11y/Announcer.tsx`, mounted at the
route shell so they persist across solo↔multi navigation. A `useAnnouncer()`
hook exposes `say(text, { assertive?: boolean })`. Backed by a small store keyed
by id so React re-renders flip the text node and the live region observes the
mutation.

The visible `CurrentCluePanel` loses its `role="status"` and `aria-live` —
purely visual after this change.

### Triggers

| Event | Channel | Text (FR) |
|---|---|---|
| Active **word** changes (focus moves into a new word) | polite | *« <clue> », mot <horizontal\|vertical> de N lettres : <pattern>* |
| Cell focus, same word | none — handled by native `<input>` aria-label | *<n>ème lettre : <I\|vide>* |
| Letter validated correct (transition empty→letter+validated) | polite | *lettre correcte* |
| Word completed | polite | *mot validé : <WORD>* |
| Validation reveals an error | assertive | *erreur dans le mot <direction>* |
| Hint revealed | polite | *lettre <X> révélée, position <n>* |
| Player joins (multi) | polite | *<name> a rejoint la partie* |
| Player leaves / disconnects (multi) | polite | *<name> s'est déconnectée* |
| Player reconnects (multi) | polite | *<name> est de retour* |
| Another player completes a word (multi) | polite | *<name> a complété un mot* |
| Game starts (multi) | polite | *La partie commence* |
| Game ends (multi) | none — handled by end-game modal focus + heading | — |

### Slot pattern format (v1)

Option **a** — terse, punctuation-spelled blanks. For word `_ _ I _ A`:

> *« <clue> », mot horizontal de 5 lettres : point, point, I, point, A*

Length-adaptive variants are deferred to a future iteration.

### De-dup and throttling

- Per-channel: identical messages within 200ms are skipped (guards against
  StrictMode double-renders and double-fires from nav code).
- Multi-player presence events for the same user within 2s are coalesced.

### Cell aria-label (native, no announcer)

The `<input>` element of each playable cell carries `aria-label` of the form:

- *<n>ème lettre : <I>* when filled
- *<n>ème lettre : vide* when empty

`<n>` is the position within the active word, not the grid coordinate. VoiceOver
reads this on focus; no announcer call is needed for cell-level events.

## 6. Skip link & focus management (PR-B1)

### Skip link

- Visually-hidden until focused. First focusable element on every route, rendered
  at the top of `AppHeader.tsx`.
- Label: *« Aller à la grille »* on grid routes; *« Aller au contenu »* elsewhere.
- Targets: a stable id on the route's main landmark (`<main id="main">`); on grid
  routes, a second skip link jumps straight to the first non-validated cell. Two
  skip links on grid routes is acceptable.

### Focus rules

| Trigger | Target |
|---|---|
| Initial puzzle render (cold load) | Nowhere — focus stays on `<body>`. The user enters the grid via Tab or skip link. |
| First Tab/click into the grid | First non-validated cell (top-left bias). |
| Puzzle reload (same route, new puzzle) | Same coordinates if still valid; otherwise first non-validated cell. Polite announce *« Nouvelle grille chargée »*. |
| Modal opens | First focusable inside the modal (ark-ui Dialog handles this). |
| Modal closes | Element that opened the modal (ark-ui handles), with a fallback to the grid's last-focused cell when the opener has unmounted. |
| Route nav | `<main>` heading. |

The fallback paths are the part that breaks in practice — explicit Playwright
tests for "modal close with opener unmounted" go in PR-B1.

## 7. Manual VoiceOver checklist (PR-B3)

Run on macOS Safari + Chrome with VO on. Each step is pass/fail and copied into
the ADR for re-runs:

1. Cold load on `/`: Tab reaches the skip link first; activating it lands focus
   on the grid main landmark.
2. Tab again enters the first non-validated cell; VO announces *« <n>ème lettre :
   vide »* (or letter).
3. Arrow-Right moves to the next cell within the same word; VO announces only
   the cell, no clue.
4. Arrow-Down crosses into a new word; VO announces clue + direction + length +
   slot pattern in option-a format, exactly once.
5. Type a wrong letter, trigger validation; assertive channel interrupts with
   *« erreur dans le mot ... »*.
6. Reveal a hint; polite channel announces *« lettre <X> révélée, position <n> »*.
7. Complete a word; polite channel announces *« mot validé : <WORD> »*.
8. Open end-game modal; focus moves inside; Esc closes; focus returns to opener.
9. Reload puzzle; focus stays on body; *« Nouvelle grille chargée »* announced.
10. Multi room: another browser joins → polite announce *« <name> a rejoint la
    partie »*. Disconnect → polite announce *« <name> s'est déconnectée »*. Other
    player completes a word → polite announce *« <name> a complété un mot »*.
11. axe e2e on the same routes returns zero `serious`/`critical` violations.

PR-B3 attaches the run output to its description.

## 8. ADR-00XX outline (lands in PR-A1)

- **Title:** Accessibility baseline (WCAG 2.2 AA + screen-reader playability)
- **Status:** Proposed → Accepted on PR-A1 merge
- **Context:** manifesto's accessibility line, no current automated gate,
  mots-fléchés-specific SR challenges (2D nav, French clues, bent arrow cells).
- **Decision:** axe via Playwright + vitest-axe; severity policy as in §4;
  two-channel announcer pattern as in §5; French as primary SR locale;
  VoiceOver/macOS as canonical SR; deferred items listed (settings UI for
  toggles, re-announce hotkey, NVDA, iOS VoiceOver).
- **Consequences:** harder to merge SR-breaking changes; ratchet to fail on
  `moderate` in 60 days; future settings PR unblocks user-controlled muting;
  ADR amendment required to relax any rule. VO native "repeat last phrase"
  (`VO+Z`) is the documented workaround for re-announce until the hotkey ships.

## 9. Manifesto compliance map

| Manifesto rule | How this branch complies |
|---|---|
| Architecture: domain has zero infra deps | Pure frontend; no domain changes. |
| Testing: TDD; mock only boundaries | Component-level vitest-axe and Playwright assertions written before the fix or feature they guard; real DOM via jsdom + Playwright; no SR mocked. |
| Trivial code untested | Skip link markup not tested directly; Playwright assertion *« Tab from body lands on skip link first »* covers the behavior. |
| Small PRs ≤400 lines | Five PRs scoped above; PR-A2 may fan out. |
| Conventional commits, lowercase first word | `feat(a11y): wire axe-core into e2e`, etc. Verified before push. |
| Branch name `<type>/<short-description>` | `feat/a11y-foundation`. |
| CI under 5 min | axe-core/playwright reuses the existing browser; ~10–20s per route, ~2 min total. Inside budget. |
| Feature flags | None — accessibility is not gated. |
| DCO sign-off | `git commit -s` for every commit. |
| WCAG AA minimum | Explicit target; CI gate fails the build. |

## 10. TDD ordering per PR

- **PR-A1** — red: failing axe e2e on a known violation we plant; green: wire
  axe and assert no `serious`+; refactor: extract per-route helper. `vitest-axe`
  matcher: red — failing component test; green — add axe assertion to a known-
  clean component; refactor — wrapper helper.
- **PR-A2 (each fix)** — red: failing axe e2e at the violating route or
  component; green: fix; refactor: typically none.
- **PR-B1** — red: Playwright assertion *Tab from body reaches skip link*
  failing; green: add skip link; same red→green for each focus rule.
- **PR-B2** — red: Playwright assertion that the announcer's hidden node
  contains the expected French string after a word transition (text-content
  check, no need to drive a real SR); green: implement announcer + word-change
  detection; refactor: split into `Announcer.tsx`, `useAnnouncer.ts`,
  `useWordTransition.ts`. Same red→green per event in the trigger matrix.
- **PR-B3** — manual checklist; no TDD step.

## 11. Open questions (resolve at plan-writing)

1. Exact route paths and IDs for the 5–6 audited routes in PR-A1.
2. Whether removing the existing clue-panel `aria-live` in PR-B2 breaks any
   currently-passing Playwright assertion in the e2e suite.
3. Whether ark-ui Dialog's focus-restore covers the "opener unmounted" edge
   case, or if PR-B1 needs a custom fallback.
4. Whether the multi room's existing socket event stream exposes the events
   listed in §5 (player join/leave/reconnect/word-completed/game-start) at
   route level, or whether PR-B2 needs to subscribe to them explicitly.
