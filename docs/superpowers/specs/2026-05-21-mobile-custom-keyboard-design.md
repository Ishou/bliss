# Custom mobile keyboard panel for the grid

**Date:** 2026-05-21
**Author:** brainstorming session with Claude
**Status:** Approved (pending implementation plan)

## Problem

On mobile devices (phones and finger-only tablets), the *mots fléchés* grid
relies on the OS virtual keyboard to enter letters. This has three downsides:

1. **Inconsistent behavior between iOS and Android** — autocomplete, autofill,
   accent suggestion bars, and dismissal gestures all differ. The grid already
   carries workarounds (e.g., `<input type="search">` to dodge Android autofill
   fingerprinting, `inputMode="text"`, viewport-resize math).
2. **No room for puzzle affordances** — hints and clue navigation live in a
   toolbar above the grid. On mobile, that toolbar gets pushed off-screen the
   moment a cell is focused and the OS keyboard pops, forcing the user to
   dismiss the keyboard to access hints.
3. **Lost screen real estate to OS chrome** — the OS keyboard reserves a row
   for space/punctuation/emoji that mots fléchés never uses.

A fully custom in-app keyboard panel solves all three: unified cross-platform
behavior, hint and clue-navigation controls one tap away from typing, and
every pixel of the panel works for the puzzle.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | **Activation**: touch-primary detection via `(any-pointer: coarse) and (any-hover: none)` | Targets phones and finger-only tablets; gracefully excludes iPad+Magic-Keyboard and desktop-with-touchscreen. Observed via `matchMedia` so plugging in a keyboard mid-session updates the UI. |
| 2 | **Letter layout**: AZERTY | French-keyboard-native muscle memory; matches user expectations for a French puzzle game. |
| 3 | **Action chrome**: full puzzle controls + clue banner | Top: clue banner (pos, text, alt-clue chip). Action row: Préc · Indice (with counter) · Suiv. Bottom-row right: ⇄ direction, ⌫ backspace. |
| 4 | **Coexistence with existing UI**: mobile-only removal | On touch-primary devices, `CurrentCluePanel` and the toolbar `HintControl` are *not rendered*. The keyboard panel is the sole owner of clue display + hint UI on mobile. Desktop is untouched. |
| 5 | **Préc/Suiv semantics**: identical to Shift+Tab / Tab today | Strict definition order (across-then-down), cycles past completed clues. Consistency with desktop trumps cleverness. |
| 6 | **Visibility**: always visible on the grid screen | No slide-up/slide-down animations. Stable layout. Cells are tap-routed to the always-visible panel. Reclaims the `visualViewport` keyboard-resize complexity on mobile (it becomes desktop-only fallback). |

## Out of scope (v1)

- **Accent keys.** The grid already strips diacritics (`é → E`, `ç → C`) via
  `stripDiacritics()`. Adding accent keys would mislead users into thinking
  accents matter to validation.
- **Haptic feedback.** Nice-to-have; defer.
- **User-facing toggle to force-on/off.** Activation is detection-only. If
  feedback shows users want override, add later.
- **Tablet-specific layout tweaks.** Touch-primary detection covers tablets;
  the same 10/10/6 AZERTY scales with the wider viewport without changes.
- **Long-clue "expand to sheet" affordance on banner tap.** `FitText` handles
  shrink; expansion is a follow-up if shrink isn't enough in practice.
- **i18n framework.** All copy is French (matches the rest of the app).

## Architecture

### Scope & boundaries

- New: `MobileKeyboard` rendered on grid routes (`/grille`, `/lobby/$lobbyId`)
  when `useTouchPrimary()` returns `true`.
- Modified: grid cell `<input>` gets `inputMode={touchPrimary ? 'none' : 'text'}`.
- Modified: `CurrentCluePanel` and `PuzzleToolbar`'s `HintControl` are
  conditionally rendered (or not rendered) when `touchPrimary` is `true`.
- Modified: `useGridNavigation`'s imperative handlers are extracted into a
  pure command-dispatch hook (the load-bearing refactor — see below).
- Preserved: cell rendering, focus management via hidden `<input>`, accent
  normalization, validation flow, presence overlays, hint API contract,
  `visualViewport` math (becomes desktop-only fallback).

### Layout, sizing & viewport math

Panel structure (bottom of viewport, fixed position):

```
┌─────────────────────────────────────────────────┐
│ Bottom row:  W X C V B N  [⇄]  [⌫]              │  ≥44px target row
│ Middle row:  Q S D F G H J K L M                │  ≥44px target row
│ Top row:     A Z E R T Y U I O P                │  ≥44px target row
│ Action row:  [◀ Préc.] [💡 Indice · 3] [Suiv. ▶]│  ≥44px target row
│ Clue banner: [pos] clue text [⇄ alt]            │  44–56px (FitText shrinks)
└─────────────────────────────────────────────────┘
```

- Each interactive element has a **hit area ≥ 44×44 CSS px** (WCAG 2.5.5 AA).
  On 320px-wide viewports (iPhone SE 1st gen) visible keys may be ~28px wide;
  the `KeyboardKey` primitive uses padding to extend the tap target to ≥44px.
  WCAG measures hit area, not visual area.
- Total panel height ~260–290px on a 390×844 viewport (~32% vertical).
- Bottom padding honors `env(safe-area-inset-bottom)`.
- Banner clue text uses the existing `FitText` component.

Grid sizing:

- Grid container `max-height` becomes `100dvh − var(--mobile-kb-height)`
  when the panel is mounted. Panel height is observed via `ResizeObserver`
  on the panel root, written to a CSS variable.
- Today's `visualViewport` math in `Grid.tsx` stays as the fallback for
  desktop. It becomes a no-op on mobile because the OS keyboard never opens.

Scrolling:

- Grid scrolls inside its container (today's behavior).
- Keyboard panel never scrolls.
- Z-index: panel above scrollbar/minimap, below modals.

### Key bindings & command dispatch

All key taps and DOM key events produce a `Command` consumed by a single
controller. Today's imperative `onInput`/`onKeyDown` handlers become thin
adapters that emit Commands.

```ts
type Command =
  | { kind: 'letter'; char: string }
  | { kind: 'backspace' }
  | { kind: 'toggle-direction' }
  | { kind: 'prev-clue' }
  | { kind: 'next-clue' }
  | { kind: 'request-hint' };

interface PuzzleInputController {
  dispatch(cmd: Command): void;
  focusedCell: { row: number; col: number; locked: boolean } | null;
  activeClue: ClueDescriptor | null;
  hintState: { remaining: number; exhausted: boolean; pending: boolean };
}
```

Per-Command behavior (all match today's desktop semantics):

- **letter** — normalize via `stripDiacritics`, write to focused cell,
  auto-advance to next cell in active clue.
- **backspace** — if focused cell has a letter, delete it; else step back
  to previous cell in active clue and delete that.
- **toggle-direction** — if focused cell is an intersection, swap active
  direction; else no-op.
- **prev-clue / next-clue** — call existing Tab/Shift+Tab cycle logic;
  strict definition order, cycles past completed clues.
- **request-hint** — identical to today's `HintControl` flow: read focused
  cell, call `onRequest(row, col)`, server picks letter, success fires
  `onReveal`. Counter and disabled state reflect `useHintRequest` state.

Banner empty state (no focused cell):

- Banner text: "Touchez une case pour commencer" (dimmed).
- Letter keys: visually disabled, taps inert.
- Indice: disabled.
- Préc./Suiv.: enabled — focus the first cell of the first/last clue
  respectively (a useful entry point).

### Component layout

```
frontend/src/ui/components/keyboard/
├── MobileKeyboard.tsx          top-level panel; composes banner + 4 rows
├── ClueBanner.tsx              pos chip + clue text (FitText) + alt chip
├── ActionRow.tsx               [Préc.] [Indice·N] [Suiv.]
├── LetterRow.tsx               renders one row of letter keys
├── KeyboardKey.tsx             single key primitive (letter or action)
├── useTouchPrimary.ts          matchMedia hook
├── usePuzzleInputController.ts Command-dispatch hook
├── azertyLayout.ts             pure data: rows × letters
└── index.ts                    barrel
```

Touched files:

- `Cell.tsx` — conditional `inputMode`.
- `Grid.tsx` — conditional `<MobileKeyboard />` render; CSS-var plumbing
  for panel height.
- `useGridNavigation.ts` — extract pure handlers; DOM-event handlers
  become Command-producing adapters.
- `PuzzleToolbar.tsx` — conditional render of `HintControl`.
- `grille.tsx` and `lobby.$lobbyId.lazy.tsx` routes — conditional render
  of `CurrentCluePanel`.

Data flow:

```
            ┌──────────────────────┐
            │  usePuzzleInputCtrl  │  ← single source of truth
            └─────────┬────────────┘
                      │ Command
        ┌─────────────┴──────────────┐
        ▼                            ▼
  Hidden <input>                MobileKeyboard
  (desktop / fallback)          (touch-primary)
        │                            │
        └─── identical dispatch ─────┘
                      │
                      ▼
       useGridNavigation pure handlers
       (focus, write, advance, cycle, hint)
                      │
                      ▼
            Puzzle state (existing)
```

State ownership: `focusedCell`, `activeClue`, `direction` stay in
`useGridNavigation`. `hintState` stays in `useHintRequest`. Controller
composes both and exposes read-only views. `touchPrimary` is the only
genuinely new state (boolean, from `matchMedia`).

Why a hook, not a context: the consumers (`MobileKeyboard`, hidden inputs)
all live under the grid component tree. One or two levels of prop drilling
beats a context that re-renders on every `focusedCell` change. Promote to
context only if a keyboard needs to live outside the grid tree later.

### Accessibility

- Panel: `<div role="group" aria-label="Clavier mots fléchés">`.
- Each key: real `<button type="button">` (native focus ring, Enter/Space
  activation, screen-reader announcement).
- Letter buttons: `aria-label="Lettre A"` (avoids screen readers reading
  "A" as the French indefinite article).
- Action button labels (French):
  - ⌫ → "Effacer"
  - ⇄ → "Changer de sens"
  - ◀ → "Indice précédent" (i.e., previous clue)
  - ▶ → "Indice suivant"
  - 💡 → "Demander un indice, N restants" (count interpolated)
- Banner clue text is read by the existing grid `aria-live="polite"`
  region; no second live region.
- Tap on a button uses `onMouseDown` → `preventDefault` to preserve focus
  on the hidden grid `<input>` (same pattern `HintControl` uses today).
  Without this, iOS Safari steals focus to the button and re-shows the OS
  keyboard on the next tap.
- `touch-action: manipulation` on keys to skip legacy 300ms double-tap zoom.
- No transitions on the panel — always visible, no slide-up. `:active`
  color change only; `prefers-reduced-motion` needs no special handling.

### Testing strategy

**Unit / component (Vitest + Testing Library)** under
`frontend/tests/keyboard/`:

- `azertyLayout.test.ts` — 26 letters, rows are 10/10/6, no duplicates.
- `useTouchPrimary.test.tsx` — mocks `matchMedia`; initial value, updates
  on change event, listener cleanup.
- `MobileKeyboard.test.tsx` —
  - 26 letter buttons rendered in correct row order.
  - Letter/backspace/direction/prev/next taps dispatch correct Commands.
  - Indice disabled when exhausted / no focused cell / cell locked.
  - Indice shows remaining count, disables during pending.
  - Buttons preserve `<input>` focus on tap (mousedown preventDefault).
- `ClueBanner.test.tsx` — pos chip + length pill + alt chip rendered from
  props; alt chip tap fires direction-toggle Command; empty state when
  `activeClue == null`.
- `usePuzzleInputController.test.tsx` — Commands map to expected grid-state
  mutations.

**Refactor regression coverage**: existing `frontend/tests/grid-input.test.tsx`
must keep passing with zero edits — the load-bearing test that the Command
refactor is behavior-preserving for desktop.

**Integration (Playwright)** at `frontend/tests/e2e/mobile-keyboard.spec.ts`:

- iPhone 13 viewport + touch emulation.
- Grid load: `MobileKeyboard` rendered; `CurrentCluePanel` and toolbar
  `HintControl` not rendered.
- Tap cell → focus + banner updates.
- Tap 6 letters → cells fill across the clue.
- Tap backspace → last cell empties.
- Tap ⇄ at intersection → direction toggles.
- Tap Suiv. → focus moves to next clue's first cell.
- Tap Indice → letter reveals, counter decrements.
- Desktop viewport: `MobileKeyboard` not rendered, existing UI present.
- 320px viewport spot-check: `getBoundingClientRect` on each button's
  padded hit area ≥ 44×44.

**A11y (axe-core via Playwright)** at
`frontend/tests/a11y/mobile-keyboard.a11y.spec.ts`:

- Mobile viewport, grid loaded, `pnpm a11y` rules over the panel.
- Verify: every button has an accessible name, color contrast on action
  vs. letter keys, no nested interactives, group has accessible name.

**Manual device test plan** (documented, not automated):

- iOS Safari (real device): OS keyboard does NOT pop on cell focus;
  panel sits flush above the home indicator.
- Android Chrome (real device): same checks; autofill not triggered.
- iPad with Magic Keyboard: `matchMedia` reports `any-hover: hover`;
  panel does NOT render.
- iPad without keyboard: panel renders; keys are appropriately sized for
  the wider viewport.

### No backend changes

The hint API contract is unchanged. No new endpoints, no schema work, no
ADR-0001 §3 schema-first dance. This is a pure frontend change.

## Implementation sequencing notes (for the plan, not this spec)

Bliss enforces a 400-line-diff cap per PR (CLAUDE.md, ADR-0001 §4 addendum).
This spec describes a feature larger than that. The implementation plan
should split into ordered PRs roughly along these lines:

1. **Command extraction refactor** — `usePuzzleInputController`,
   `useGridNavigation` adapter pass. Net diff small; existing tests
   stay green. Ships dark (no user-facing change).
2. **Touch detection + `inputMode` conditional** — `useTouchPrimary`,
   `Cell.tsx` change. Tiny.
3. **`MobileKeyboard` component + AZERTY layout** — visible panel,
   no banner/nav yet. Letters + backspace.
4. **Clue banner + action row** — Préc/Suiv + Indice + ⇄.
5. **Mobile-only conditional rendering** of `CurrentCluePanel` and
   toolbar `HintControl`. Plus integration tests.
6. **A11y polish + Playwright suite**.

The plan should also call out the manual device test pass before the final
PR ships (CLAUDE.md "verify, don't guess").
