# Mobile Custom Keyboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the OS virtual keyboard on touch-primary devices with a fully-custom in-app keyboard panel that integrates the clue banner, hint button, and clue navigation directly inside it.

**Architecture:** A new `MobileKeyboard` component is mounted at the bottom of grid routes when `useTouchPrimary()` returns true. Existing imperative grid handlers in `useGridNavigation` are extracted into named methods (`enterLetter`, `eraseLetter`, `toggleDirection`, `cycleClue`) that both the hidden `<input>`'s DOM-event adapters and the keyboard buttons call. On touch-primary devices, `CurrentCluePanel` and the toolbar's `HintControl` are not rendered — the panel owns clue + hint UI. Desktop is untouched.

**Tech Stack:** React 19, TypeScript, Panda CSS (`styled-system/css`), Vitest + Testing Library, Playwright + axe-core. No new runtime dependencies.

**Source spec:** [`docs/superpowers/specs/2026-05-21-mobile-custom-keyboard-design.md`](../specs/2026-05-21-mobile-custom-keyboard-design.md)

**PR sequencing:** Each phase below is one PR (≤400 lines of diff per CLAUDE.md). PRs land in order; later PRs depend on earlier ones.

## Plan divergences from spec

- **Command dispatch shape.** Spec described a `Command` discriminated union routed through a single `dispatch(cmd)` function. The plan delivers the same outcome with named methods on the `GridNavigation` interface (`enterLetter`, `eraseLetter`, `cycleClue`, plus the existing `toggleDirection`). Same execution path; one less layer of indirection. The methods are callable from both DOM event adapters and button clicks, which is what the spec required.
- **Focused-cell-locked is read imperatively, not threaded as a reactive prop.** ADR-0002 §4 (uncontrolled-input contract) and the existing `HintControl` pattern (see comment at `HintControl.tsx:7`) keep per-cell state out of the React tree to avoid re-renders on every focus change. `MobileKeyboard` follows the same pattern: it accepts a `getFocusedCell: () => FocusedCell | null` callback that the route provides, and the hint button calls it at click time rather than reading a reactive prop.

## Pinch-zoom: grid zooms, keyboard does not — no special code needed

The grid is pinch-zoomable via `react-zoom-pan-pinch`'s `TransformWrapper` (see comment at `Grid.tsx:155-162`). The library applies a CSS transform **only inside** the wrapper. The `MobileKeyboard` panel is mounted at `position: fixed; insetBlockEnd: 0`, **outside** the wrapper subtree. Net effect: grid pinch scales the grid contents; the keyboard panel is unaffected because it never receives that transform.

Browser-level page pinch (different mechanism — `window.visualViewport` zoom) is allowed by the viewport meta tag for WCAG 1.4.4 compliance. If a user pinches the page, every element including the keyboard scales naturally — which is the *correct* accessibility behaviour for a low-vision user who wants the keyboard glyphs bigger. We deliberately do not counter-scale.

`CurrentCluePanel`'s `useVisualViewportZoom` (`CurrentCluePanel.tsx:304-347`) solves a different problem: a *sticky-top* element needs to be re-anchored to the visual-viewport top during browser pinch, otherwise scrolling can push it off-screen. A bottom-anchored fixed element does not have this issue — `insetBlockEnd: 0` always reaches the bottom of the layout viewport, which is what we want.

---

## Phase 1: Extract grid-input commands as imperative methods

**Goal:** Refactor `useGridNavigation` so the imperative work currently buried inside `handleKeyDown` / `handleInput` branches is exposed as named methods on the returned `GridNavigation` interface. The DOM-event handlers stay (they still `preventDefault` and bridge DOM events), but they delegate to the new methods. Ships dark.

**Testing strategy for this phase.** This phase is a behaviour-preserving refactor — no new behaviour is added, so no new tests are written. The existing `frontend/tests/grid-input.test.tsx` suite (1,300+ lines) drives the new methods transitively: every `fireEvent.keyDown` already lands in `handleKeyDown`, which now delegates to the extracted methods. If the extraction is correct, every existing assertion stays green. **The existing suite IS the regression guard.** MANIFESTO: "Tests verify behavior, not structure."

**Branch:** `refactor/grid-input-commands`

### Task 1: Extract `enterLetter(char)`

**Files:**
- Modify: `frontend/src/ui/components/grid/useGridNavigation.ts` (interface near line 30; impl in `handleKeyDown` around line 688–720)

- [ ] **Step 1: Confirm the existing test suite is green at HEAD**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS. This is the baseline — if anything is already red, fix that first (it is not part of this work).

- [ ] **Step 2: Add `enterLetter` to the `GridNavigation` interface**

In `useGridNavigation.ts`, add to the interface (after the existing `toggleDirection` declaration around line 62):

```ts
readonly enterLetter: (char: string) => void;
readonly eraseLetter: () => void;
readonly cycleClue: (step: 1 | -1) => void;
```

- [ ] **Step 3: Implement `enterLetter` and delegate from `handleKeyDown`**

Add inside `useGridNavigation` (anywhere before `handleKeyDown`):

```ts
const enterLetter = useCallback(
  (char: string) => {
    const { focused: f, direction: dir } = stateRef.current;
    if (!f) return;
    const letter = stripDiacritics(char);
    if (letter.length !== 1 || !LETTER_RE.test(letter)) return;
    const el = refs.current.get(key(f));
    if (el && !el.readOnly) {
      const before = el.value;
      el.value = letter;
      if (before !== letter) {
        cellValuesRef.current.set(key(f), letter);
        bumpEntries();
        onCellChangeRef.current?.(f.row, f.col, letter);
      }
      onCellFilledRef.current?.(f, dir);
    }
    const clue = lookup.clueAt(f.row, f.col, dir);
    if (!clue) return;
    const idx = clue.cells.findIndex((c) => same(c.position, f));
    if (idx >= 0 && idx < clue.cells.length - 1) focusCell(clue.cells[idx + 1].position);
  },
  [bumpEntries, focusCell, lookup],
);
```

Replace the printable-letter branch in `handleKeyDown` (around line 688–720) with:

```ts
if (k.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && LETTER_RE.test(k)) {
  event.preventDefault();
  enterLetter(k);
  return;
}
```

Add `enterLetter` to the returned object at the bottom of the hook.

- [ ] **Step 4: Run the existing tests — they MUST stay green**

```sh
cd frontend && pnpm test -- grid-input grid-presence
```

Expected: PASS. Every assertion that fires a letter keydown now indirectly tests `enterLetter`. If any test fails, the refactor changed observable behaviour — re-read the failing test, compare the new `enterLetter` body against the original branch you replaced, and reconcile.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/grid/useGridNavigation.ts
git commit -s -m "refactor(frontend-grid): extract enterLetter from handleKeyDown"
```

### Task 2: Extract `eraseLetter()`

**Files:**
- Modify: `frontend/src/ui/components/grid/useGridNavigation.ts` (Backspace branch around line 760–792)

- [ ] **Step 1: Implement `eraseLetter` and delegate from `handleKeyDown`**

Add inside `useGridNavigation`:

```ts
const eraseLetter = useCallback(() => {
  const { focused: f, direction: dir } = stateRef.current;
  if (!f) return;
  const el = refs.current.get(key(f));
  if (el && el.value !== '' && !el.readOnly) {
    el.value = '';
    cellValuesRef.current.delete(key(f));
    bumpEntries();
    onCellChangeRef.current?.(f.row, f.col, null);
    return;
  }
  const clue = lookup.clueAt(f.row, f.col, dir);
  if (!clue) return;
  const idx = clue.cells.findIndex((c) => same(c.position, f));
  if (idx <= 0) return;
  const prev = clue.cells[idx - 1].position;
  const prevEl = refs.current.get(key(prev));
  if (prevEl && !prevEl.readOnly) {
    const before = prevEl.value;
    prevEl.value = '';
    if (before !== '') {
      cellValuesRef.current.delete(key(prev));
      bumpEntries();
      onCellChangeRef.current?.(prev.row, prev.col, null);
    }
  }
  focusCell(prev);
}, [bumpEntries, focusCell, lookup]);
```

Replace the Backspace `case` body in `handleKeyDown` with:

```ts
case 'Backspace': {
  event.preventDefault();
  eraseLetter();
  return;
}
```

Add `eraseLetter` to the returned object.

- [ ] **Step 2: Run the existing tests**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS. The existing suite covers Backspace behaviour at the keydown level; every assertion exercises the extraction transitively.

- [ ] **Step 3: Commit**

```sh
git add frontend/src/ui/components/grid/useGridNavigation.ts
git commit -s -m "refactor(frontend-grid): extract eraseLetter from handleKeyDown"
```

### Task 3: Extract `cycleClue(step)`

**Files:**
- Modify: `frontend/src/ui/components/grid/useGridNavigation.ts` (Tab / Enter branch around line 651–685)

- [ ] **Step 1: Implement `cycleClue` and delegate from `handleKeyDown`**

Add inside `useGridNavigation`:

```ts
const cycleClue = useCallback(
  (step: 1 | -1) => {
    const { focused: f, direction: dir } = stateRef.current;
    const clues = lookup.orderedClues;
    if (clues.length === 0) return;
    let nextClue: Clue;
    if (!f) {
      nextClue = step === -1 ? clues[clues.length - 1] : clues[0];
    } else {
      const here = lookup.cluesAt(f.row, f.col);
      const current = here.find((h) => h.direction === dir) ?? here[0];
      const currentIdx = current ? clues.indexOf(current) : -1;
      const baseIdx = currentIdx < 0 ? (step === -1 ? 0 : -1) : currentIdx;
      const nextIdx = (baseIdx + step + clues.length) % clues.length;
      nextClue = clues[nextIdx];
    }
    if (nextClue.direction !== stateRef.current.direction) setDirection(nextClue.direction);
    focusCell(nextClue.cells[0].position);
  },
  [focusCell, lookup],
);
```

Replace the `if (k === 'Tab' || k === 'Enter')` block in `handleKeyDown` with:

```ts
if (k === 'Tab' || k === 'Enter') {
  event.preventDefault();
  cycleClue(event.shiftKey ? -1 : 1);
  return;
}
```

Add `cycleClue` to the returned object.

- [ ] **Step 2: Run the existing tests**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS. Existing suite covers Tab / Enter / Shift+Tab / Shift+Enter cycling.

- [ ] **Step 3: Commit**

```sh
git add frontend/src/ui/components/grid/useGridNavigation.ts
git commit -s -m "refactor(frontend-grid): extract cycleClue from handleKeyDown"
```

### Task 4: Open Phase 1 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin refactor/grid-input-commands
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "refactor(frontend-grid): extract input commands from key handlers" --body "$(cat <<'EOF'
## Summary
- Adds `enterLetter`, `eraseLetter`, `cycleClue` methods to the `GridNavigation` interface returned by `useGridNavigation`.
- `handleKeyDown` is now a thin DOM adapter: it preventDefaults and calls the new methods.
- Existing behavior preserved — `frontend/tests/grid-input.test.tsx` passes with zero edits to its original assertions.
- Phase 1 of the mobile custom keyboard rollout (`docs/superpowers/specs/2026-05-21-mobile-custom-keyboard-design.md`). Ships dark; no user-facing change.

## Test plan
- [ ] `pnpm test -- grid-input grid-presence` green locally with zero test edits (regression guarantee).
- [ ] `pnpm typecheck` green.
- [ ] CI `frontend-build`, `ci`, `commitlint`, `dco` all green.
EOF
)"
```

---

## Phase 2: Touch-primary detection + `inputMode="none"` gate

**Goal:** Add `useTouchPrimary` hook and make grid cell `<input>` set `inputMode="none"` when the device is touch-primary, preventing the OS keyboard from opening. No visible UI change yet (no replacement panel — typing via the on-screen OS keyboard simply becomes unreachable on touch-primary devices, *but* tests still drive `enterLetter` directly so puzzles remain solvable through Phase 3, which lands the actual panel).

Because Phase 2 leaves touch-primary users with no way to type until Phase 3 ships, **Phase 2 and Phase 3 must merge together on `main`** — either back-to-back the same day, or by holding Phase 2 in `next` until Phase 3 is reviewed.

**Branch:** `feat/frontend-touch-primary-input-mode`

### Task 1: Create `useTouchPrimary` hook

**Files:**
- Create: `frontend/src/ui/components/keyboard/useTouchPrimary.ts`
- Test: `frontend/tests/use-touch-primary.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useTouchPrimary } from '@/ui/components/keyboard/useTouchPrimary';

type Listener = (ev: MediaQueryListEvent) => void;

function mockMatchMedia(initial: boolean) {
  const listeners = new Set<Listener>();
  const mql: MediaQueryList = {
    matches: initial,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: (_: 'change', l: Listener) => listeners.add(l),
    removeEventListener: (_: 'change', l: Listener) => listeners.delete(l),
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  };
  window.matchMedia = vi.fn().mockReturnValue(mql);
  return {
    mql,
    emit: (matches: boolean) => {
      (mql as { matches: boolean }).matches = matches;
      listeners.forEach((l) => l({ matches } as MediaQueryListEvent));
    },
    listenerCount: () => listeners.size,
  };
}

describe('useTouchPrimary', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('returns the initial match value', () => {
    mockMatchMedia(true);
    const { result } = renderHook(() => useTouchPrimary());
    expect(result.current).toBe(true);
  });

  it('updates when the media query changes', () => {
    const m = mockMatchMedia(false);
    const { result } = renderHook(() => useTouchPrimary());
    expect(result.current).toBe(false);
    act(() => m.emit(true));
    expect(result.current).toBe(true);
  });

  it('removes the listener on unmount', () => {
    const m = mockMatchMedia(false);
    const { unmount } = renderHook(() => useTouchPrimary());
    expect(m.listenerCount()).toBe(1);
    unmount();
    expect(m.listenerCount()).toBe(0);
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- use-touch-primary
```

Expected: FAIL with "Cannot find module '@/ui/components/keyboard/useTouchPrimary'".

- [ ] **Step 3: Implement the hook**

Create `frontend/src/ui/components/keyboard/useTouchPrimary.ts`:

```ts
import { useEffect, useState } from 'react';

const QUERY = '(any-pointer: coarse) and (any-hover: none)';

export function useTouchPrimary(): boolean {
  const [matches, setMatches] = useState<boolean>(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(QUERY).matches;
  });
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(QUERY);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener('change', handler);
    setMatches(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, []);
  return matches;
}
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- use-touch-primary
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/useTouchPrimary.ts frontend/tests/use-touch-primary.test.tsx
git commit -s -m "feat(frontend-keyboard): add useTouchPrimary detection hook"
```

### Task 2: Wire `inputMode="none"` on touch-primary into `Cell.tsx`

**Files:**
- Modify: `frontend/src/ui/components/grid/Cell.tsx` (around line 823)
- Test: `frontend/tests/grid-input.test.tsx` (extend with a touch-primary case)

The `<input>` in `LetterCellView` is rendered by props passed down from `Grid.tsx`. The cleanest path: read `useTouchPrimary()` once in `Grid.tsx` and pass it as a prop into `Cell.tsx`.

- [ ] **Step 1: Write a failing test**

Append to `frontend/tests/grid-input.test.tsx`:

```tsx
it('renders inputMode="none" on every letter input when touch-primary', () => {
  const original = window.matchMedia;
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList);
  try {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const inputs = container.querySelectorAll<HTMLInputElement>('[data-cell-kind="letter"]');
    expect(inputs.length).toBeGreaterThan(0);
    for (const i of inputs) expect(i.inputMode).toBe('none');
  } finally {
    window.matchMedia = original;
  }
});

it('keeps inputMode="text" when not touch-primary', () => {
  const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
  const input = container.querySelector<HTMLInputElement>('[data-cell-kind="letter"]')!;
  expect(input.inputMode).toBe('text');
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: FAIL — the touch-primary test sees `inputMode="text"` (today's hard-coded value).

- [ ] **Step 3: Pass `touchPrimary` through Grid → Cell**

In `frontend/src/ui/components/grid/Grid.tsx`, near the top of the `Grid` component body:

```tsx
import { useTouchPrimary } from '@/ui/components/keyboard/useTouchPrimary';
// ...
const touchPrimary = useTouchPrimary();
```

In `frontend/src/ui/components/grid/Cell.tsx`, add `touchPrimary` to `LetterCellView`'s props (around line 660–693):

```tsx
export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel, inWord, focused, validated, error, presence, incomingArrows, inputRef, touchPrimary, onClick, onKeyDown, onFocus, onBlur, onInput,
}: {
  // ... existing props ...
  touchPrimary: boolean;
  // ... existing handlers ...
}) {
```

And replace `inputMode="text"` (line 823) with `inputMode={touchPrimary ? 'none' : 'text'}`.

In `Grid.tsx`, find every `<LetterCellView ... />` render (there is typically one render site in the cells.map block) and pass `touchPrimary={touchPrimary}`.

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS. Both the new touch-primary case and the existing default-text case green.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/grid/Cell.tsx frontend/src/ui/components/grid/Grid.tsx frontend/tests/grid-input.test.tsx
git commit -s -m "feat(frontend-grid): gate inputMode by useTouchPrimary"
```

### Task 3: Open Phase 2 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin feat/frontend-touch-primary-input-mode
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "feat(frontend-keyboard): touch-primary detection + inputMode gate" --body "$(cat <<'EOF'
## Summary
- New `useTouchPrimary` hook reads `(any-pointer: coarse) and (any-hover: none)` via `matchMedia` and updates on change.
- Grid cell `<input>` switches to `inputMode="none"` on touch-primary, preventing the OS virtual keyboard from opening.
- Phase 2 of the mobile custom keyboard rollout. Coordinated with Phase 3 — see PR description on holding for Phase 3 before merging to main.

## Test plan
- [ ] `pnpm test -- grid-input use-touch-primary` green.
- [ ] `pnpm typecheck` green.
- [ ] Manual: open `/grille` in mobile Safari devtools (iPhone 13 preset) — OS keyboard does not appear on cell focus.
- [ ] Manual: same route on desktop Chrome — OS keyboard never expected; behavior unchanged.
EOF
)"
```

---

## Phase 3: `MobileKeyboard` letters + backspace (always visible)

**Goal:** Render the AZERTY letter grid + backspace as a fixed bottom panel on touch-primary devices. Letters and backspace call into the Phase-1 commands. No clue banner, no clue navigation, no hint button yet — those land in Phase 4. Visually the panel sits at the bottom of `Grid.tsx`'s viewport.

**Branch:** `feat/frontend-mobile-keyboard-letters`

### Task 1: Define the AZERTY layout data

**Files:**
- Create: `frontend/src/ui/components/keyboard/azertyLayout.ts`
- Test: `frontend/tests/azerty-layout.test.ts`

- [ ] **Step 1: Write a failing test**

```ts
import { describe, it, expect } from 'vitest';
import { AZERTY_ROWS } from '@/ui/components/keyboard/azertyLayout';

describe('azertyLayout', () => {
  it('has three rows of 10, 10, 6 keys', () => {
    expect(AZERTY_ROWS.map((r) => r.length)).toEqual([10, 10, 6]);
  });

  it('contains every letter from A to Z exactly once', () => {
    const all = AZERTY_ROWS.flat();
    expect(new Set(all).size).toBe(26);
    expect([...all].sort().join('')).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  });

  it('rows reflect French AZERTY ordering', () => {
    expect(AZERTY_ROWS[0]).toEqual(['A','Z','E','R','T','Y','U','I','O','P']);
    expect(AZERTY_ROWS[1]).toEqual(['Q','S','D','F','G','H','J','K','L','M']);
    expect(AZERTY_ROWS[2]).toEqual(['W','X','C','V','B','N']);
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- azerty-layout
```

Expected: FAIL ("Cannot find module ...").

- [ ] **Step 3: Implement the data**

Create `frontend/src/ui/components/keyboard/azertyLayout.ts`:

```ts
export const AZERTY_ROWS: readonly (readonly string[])[] = [
  ['A','Z','E','R','T','Y','U','I','O','P'],
  ['Q','S','D','F','G','H','J','K','L','M'],
  ['W','X','C','V','B','N'],
] as const;
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- azerty-layout
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/azertyLayout.ts frontend/tests/azerty-layout.test.ts
git commit -s -m "feat(frontend-keyboard): add AZERTY layout data"
```

### Task 2: Build the `KeyboardKey` primitive

**Files:**
- Create: `frontend/src/ui/components/keyboard/KeyboardKey.tsx`
- Test: `frontend/tests/keyboard-key.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { KeyboardKey } from '@/ui/components/keyboard/KeyboardKey';

describe('KeyboardKey', () => {
  it('renders a button with the given label and aria-label', () => {
    const { getByRole } = render(<KeyboardKey label="A" ariaLabel="Lettre A" onPress={() => undefined} />);
    const btn = getByRole('button', { name: 'Lettre A' });
    expect(btn.textContent).toBe('A');
    expect(btn.getAttribute('type')).toBe('button');
  });

  it('calls onPress on click', () => {
    const onPress = vi.fn();
    const { getByRole } = render(<KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />);
    fireEvent.click(getByRole('button'));
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('calls preventDefault on mousedown to preserve focus elsewhere', () => {
    const { getByRole } = render(<KeyboardKey label="A" ariaLabel="Lettre A" onPress={() => undefined} />);
    const ev = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    getByRole('button').dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });

  it('disabled=true blocks onPress and sets aria-disabled', () => {
    const onPress = vi.fn();
    const { getByRole } = render(<KeyboardKey label="A" ariaLabel="Lettre A" disabled onPress={onPress} />);
    fireEvent.click(getByRole('button'));
    expect(onPress).not.toHaveBeenCalled();
    expect(getByRole('button').getAttribute('aria-disabled')).toBe('true');
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- keyboard-key
```

Expected: FAIL ("Cannot find module ...").

- [ ] **Step 3: Implement the primitive**

Create `frontend/src/ui/components/keyboard/KeyboardKey.tsx`:

```tsx
import { type ReactNode, useCallback, type MouseEvent } from 'react';
import { css } from 'styled-system/css';

const keyBase = css({
  flex: 1,
  minWidth: 0,
  minHeight: '44px',
  paddingBlock: '4px',
  paddingInline: '4px',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: '7px',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontWeight: 'medium',
  fontSize: '16px',
  cursor: 'pointer',
  touchAction: 'manipulation',
  boxShadow: '0 1px 0 rgba(0,0,0,0.06)',
  transition: 'transform 80ms ease-out, background-color 80ms ease-out',
  _active: { transform: 'scale(0.96)', bg: 'surfaceElevated' },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const keyAction = css({
  bg: 'surfaceElevated',
  fontSize: '13px',
  color: 'fgMuted',
});

export interface KeyboardKeyProps {
  readonly label: ReactNode;
  readonly ariaLabel: string;
  readonly onPress: () => void;
  readonly disabled?: boolean;
  readonly variant?: 'letter' | 'action';
}

export function KeyboardKey({ label, ariaLabel, onPress, disabled = false, variant = 'letter' }: KeyboardKeyProps) {
  const handleMouseDown = useCallback((e: MouseEvent) => {
    e.preventDefault();
  }, []);
  const handleClick = useCallback(() => {
    if (disabled) return;
    onPress();
  }, [disabled, onPress]);
  return (
    <button
      type="button"
      className={variant === 'action' ? `${keyBase} ${keyAction}` : keyBase}
      aria-label={ariaLabel}
      aria-disabled={disabled || undefined}
      onMouseDown={handleMouseDown}
      onClick={handleClick}
    >
      {label}
    </button>
  );
}
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- keyboard-key
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/KeyboardKey.tsx frontend/tests/keyboard-key.test.tsx
git commit -s -m "feat(frontend-keyboard): add KeyboardKey button primitive"
```

### Task 3: Build the `MobileKeyboard` shell (letters + backspace)

**Files:**
- Create: `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`
- Create: `frontend/src/ui/components/keyboard/index.ts`
- Test: `frontend/tests/mobile-keyboard.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { MobileKeyboard } from '@/ui/components/keyboard';

const noop = () => undefined;

const baseProps = {
  onLetter: noop,
  onBackspace: noop,
};

describe('MobileKeyboard letters + backspace', () => {
  it('renders all 26 letter buttons', () => {
    const { getAllByRole } = render(<MobileKeyboard {...baseProps} />);
    const buttons = getAllByRole('button');
    const labels = buttons.map((b) => b.getAttribute('aria-label')).filter(Boolean);
    for (const ch of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
      expect(labels).toContain(`Lettre ${ch}`);
    }
  });

  it('clicking a letter calls onLetter with that character', () => {
    const onLetter = vi.fn();
    const { getByLabelText } = render(<MobileKeyboard {...baseProps} onLetter={onLetter} />);
    fireEvent.click(getByLabelText('Lettre E'));
    expect(onLetter).toHaveBeenCalledWith('E');
  });

  it('clicking backspace calls onBackspace', () => {
    const onBackspace = vi.fn();
    const { getByLabelText } = render(<MobileKeyboard {...baseProps} onBackspace={onBackspace} />);
    fireEvent.click(getByLabelText('Effacer'));
    expect(onBackspace).toHaveBeenCalled();
  });

  it('panel has role=group with accessible name', () => {
    const { getByRole } = render(<MobileKeyboard {...baseProps} />);
    expect(getByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: FAIL ("Cannot find module ...").

- [ ] **Step 3: Implement the shell**

Create `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`:

```tsx
import { css } from 'styled-system/css';
import { AZERTY_ROWS } from './azertyLayout';
import { KeyboardKey } from './KeyboardKey';

const panel = css({
  position: 'fixed',
  insetInline: 0,
  insetBlockEnd: 0,
  bg: 'surface',
  borderTop: '1px solid token(colors.border)',
  paddingInline: '6px',
  paddingBlockStart: '8px',
  paddingBlockEnd: 'calc(env(safe-area-inset-bottom) + 8px)',
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  zIndex: 20,
});

const row = css({
  display: 'flex',
  gap: '4px',
  justifyContent: 'center',
});

export interface MobileKeyboardProps {
  readonly onLetter: (char: string) => void;
  readonly onBackspace: () => void;
}

export function MobileKeyboard({ onLetter, onBackspace }: MobileKeyboardProps) {
  return (
    <div className={panel} role="group" aria-label="Clavier mots fléchés">
      {AZERTY_ROWS.map((letters, rowIdx) => (
        <div key={rowIdx} className={row}>
          {letters.map((ch) => (
            <KeyboardKey
              key={ch}
              label={ch}
              ariaLabel={`Lettre ${ch}`}
              onPress={() => onLetter(ch)}
            />
          ))}
          {rowIdx === AZERTY_ROWS.length - 1 ? (
            <KeyboardKey
              label="⌫"
              ariaLabel="Effacer"
              variant="action"
              onPress={onBackspace}
            />
          ) : null}
        </div>
      ))}
    </div>
  );
}
```

Create `frontend/src/ui/components/keyboard/index.ts`:

```ts
export { MobileKeyboard, type MobileKeyboardProps } from './MobileKeyboard';
export { KeyboardKey, type KeyboardKeyProps } from './KeyboardKey';
export { useTouchPrimary } from './useTouchPrimary';
export { AZERTY_ROWS } from './azertyLayout';
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/MobileKeyboard.tsx frontend/src/ui/components/keyboard/index.ts frontend/tests/mobile-keyboard.test.tsx
git commit -s -m "feat(frontend-keyboard): MobileKeyboard letters + backspace shell"
```

### Task 4: Mount `MobileKeyboard` from `Grid.tsx` when touch-primary

**Files:**
- Modify: `frontend/src/ui/components/grid/Grid.tsx`
- Test: `frontend/tests/grid-input.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
it('mounts MobileKeyboard on touch-primary; absent otherwise', () => {
  const original = window.matchMedia;
  const setTouchPrimary = (val: boolean) => {
    window.matchMedia = vi.fn().mockReturnValue({
      matches: val,
      media: '(any-pointer: coarse) and (any-hover: none)',
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => true,
    } as MediaQueryList);
  };
  try {
    setTouchPrimary(true);
    const r1 = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(r1.queryByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
    r1.unmount();
    setTouchPrimary(false);
    const r2 = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(r2.queryByRole('group', { name: 'Clavier mots fléchés' })).toBeNull();
  } finally {
    window.matchMedia = original;
  }
});

it('tapping a keyboard letter writes it into the focused cell', () => {
  const original = window.matchMedia;
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList);
  try {
    const { container, getByLabelText } = render(<Grid puzzle={TEST_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    fireEvent.click(getByLabelText('Lettre A'));
    expect(inputAt(container, 1, 1)!.value).toBe('A');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  } finally {
    window.matchMedia = original;
  }
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: FAIL — no `MobileKeyboard` is rendered yet from `Grid.tsx`.

- [ ] **Step 3: Mount `MobileKeyboard` in `Grid.tsx`**

In `frontend/src/ui/components/grid/Grid.tsx`, add the import near the other component imports:

```tsx
import { MobileKeyboard } from '@/ui/components/keyboard';
```

`touchPrimary` is already in scope from Phase 2 Task 2. Find where `Grid` returns its top-level JSX (search for the `<CurrentCluePanel` render around line 950). Add — sibling to the grid content — the panel:

```tsx
{touchPrimary ? (
  <MobileKeyboard
    onLetter={(ch) => nav.enterLetter(ch)}
    onBackspace={() => nav.eraseLetter()}
  />
) : null}
```

(Exact placement: outside any zoom/transform wrapper, so it's anchored to the layout viewport, not to the panned grid. The `position: fixed` on the panel does the rest.)

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/grid/Grid.tsx frontend/tests/grid-input.test.tsx
git commit -s -m "feat(frontend-grid): mount MobileKeyboard on touch-primary"
```

### Task 5: Reserve space under the grid for the panel

**Files:**
- Modify: `frontend/src/ui/components/keyboard/MobileKeyboard.tsx` (publish height via CSS var)
- Modify: `frontend/src/ui/components/grid/Grid.tsx` (consume the var in the grid wrapper's max-height)
- Test: `frontend/tests/mobile-keyboard.test.tsx`

The grid scrolls inside its container today. With the panel mounted, the container's `max-height` needs to subtract the panel height so the bottom edge of the grid stays above the panel.

- [ ] **Step 1: Write a failing test**

```tsx
it('publishes its measured height as --mobile-kb-height on document root', async () => {
  const { unmount } = render(<MobileKeyboard onLetter={() => undefined} onBackspace={() => undefined} />);
  // ResizeObserver fires async — wait one microtask + frame.
  await new Promise((r) => requestAnimationFrame(() => r(null)));
  const val = document.documentElement.style.getPropertyValue('--mobile-kb-height');
  expect(val).toMatch(/^\d+px$/);
  unmount();
  expect(document.documentElement.style.getPropertyValue('--mobile-kb-height')).toBe('');
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: FAIL (var never written; or written but not cleared).

- [ ] **Step 3: Implement the ResizeObserver**

In `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`, add a ref + effect. Imports change to:

```tsx
import { useEffect, useRef } from 'react';
```

Inside the component, before the `return`:

```tsx
const panelRef = useRef<HTMLDivElement | null>(null);
useEffect(() => {
  const el = panelRef.current;
  if (!el || typeof ResizeObserver === 'undefined') return;
  const ro = new ResizeObserver(() => {
    document.documentElement.style.setProperty('--mobile-kb-height', `${Math.ceil(el.getBoundingClientRect().height)}px`);
  });
  ro.observe(el);
  return () => {
    ro.disconnect();
    document.documentElement.style.removeProperty('--mobile-kb-height');
  };
}, []);
```

Add `ref={panelRef}` on the panel `<div>`.

In `frontend/src/ui/components/grid/Grid.tsx`, find the grid wrapper element (the one that has `overflow: auto` or a `maxHeight`/`height` constraint). Add to its inline style or class:

```tsx
maxHeight: 'calc(100dvh - var(--mobile-kb-height, 0px))'
```

(If the existing element already uses a Panda `css({...})` rule for max-height, replace its `maxHeight` value with the calc above. Otherwise, attach the calc via inline `style`.)

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/MobileKeyboard.tsx frontend/src/ui/components/grid/Grid.tsx frontend/tests/mobile-keyboard.test.tsx
git commit -s -m "feat(frontend-keyboard): publish panel height; reserve grid space"
```

### Task 6: Open Phase 3 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin feat/frontend-mobile-keyboard-letters
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "feat(frontend-keyboard): MobileKeyboard letters + backspace" --body "$(cat <<'EOF'
## Summary
- AZERTY letter layout data + `KeyboardKey` button primitive + `MobileKeyboard` shell mounted at the bottom of the grid on touch-primary devices.
- Wires letter taps → `nav.enterLetter`; backspace → `nav.eraseLetter` (both from Phase 1).
- Panel publishes its height as `--mobile-kb-height` so the grid container reserves space.
- Grid pinch-zoom (react-zoom-pan-pinch) keeps working — the keyboard panel mounts outside the TransformWrapper subtree so the grid's CSS transform doesn't touch it. No counter-scale code needed; browser-level page pinch (rare on mobile, when used it's a WCAG 1.4.4 accessibility action) scales the keyboard along with everything else, which is the correct behaviour.
- Phase 3 of the mobile custom keyboard rollout. Together with Phase 2, this makes touch-primary devices solvable again through the custom panel.

## Test plan
- [ ] `pnpm test -- mobile-keyboard keyboard-key azerty-layout grid-input` green.
- [ ] `pnpm typecheck` green.
- [ ] Manual (mobile Safari devtools, iPhone 13 preset): tap a cell → panel sits at viewport bottom; tap A → letter lands; tap backspace → letter clears.
- [ ] Manual (mobile Safari, real device, grid pinch via react-zoom-pan-pinch): grid contents enlarge; keyboard stays put at the bottom, unchanged size.
- [ ] Manual (desktop): panel absent; OS keyboard never expected; behavior unchanged.
EOF
)"
```

---

## Phase 4: Clue banner + action row (Préc · Indice · Suiv · ⇄)

**Goal:** Add the clue banner above the letter rows, plus the action row (prev clue · hint · next clue) and the direction-toggle key on the bottom row. The Indice button replaces the existing toolbar `HintControl` for touch-primary users (the toolbar gating happens in Phase 5).

**Branch:** `feat/frontend-mobile-keyboard-banner-actions`

### Task 1: Build `ClueBanner`

**Files:**
- Create: `frontend/src/ui/components/keyboard/ClueBanner.tsx`
- Test: `frontend/tests/clue-banner.test.tsx`

Reuses the `Clue` type from `useGridNavigation`. Shows: direction chip (across/down), clue text via `FitText`, length pill, alternate clue chip (tap to toggle direction), or an empty-state hint when `clue == null`.

- [ ] **Step 1: Write a failing test**

```tsx
import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ClueBanner } from '@/ui/components/keyboard/ClueBanner';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const makeClue = (text: string, len: number, dir: 'across' | 'down'): Clue => ({
  definition: { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text, arrow: dir === 'across' ? 'right' : 'down' }] },
  clue: { text, arrow: dir === 'across' ? 'right' : 'down' },
  direction: dir,
  cells: Array.from({ length: len }, (_, i) => ({ kind: 'letter', position: { row: 0, col: i + 1 }, entry: '' })),
});

describe('ClueBanner', () => {
  it('renders the clue text and length', () => {
    const { getByText } = render(
      <ClueBanner clue={makeClue('Fruit jaune', 6, 'across')} alternateClue={null} onToggleDirection={() => undefined} />,
    );
    expect(getByText('Fruit jaune')).toBeTruthy();
    expect(getByText('6')).toBeTruthy();
  });

  it('renders the empty-state hint when clue is null', () => {
    const { getByText } = render(<ClueBanner clue={null} alternateClue={null} onToggleDirection={() => undefined} />);
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });

  it('tapping the alt-clue chip fires onToggleDirection (and not on mousedown focus shift)', () => {
    const onToggleDirection = vi.fn();
    const { getByLabelText } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={makeClue('Vert', 4, 'down')}
        onToggleDirection={onToggleDirection}
      />,
    );
    const chip = getByLabelText(/Basculer/i);
    const md = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    chip.dispatchEvent(md);
    expect(md.defaultPrevented).toBe(true);
    fireEvent.click(chip);
    expect(onToggleDirection).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- clue-banner
```

Expected: FAIL ("Cannot find module ...").

- [ ] **Step 3: Implement `ClueBanner`**

Create `frontend/src/ui/components/keyboard/ClueBanner.tsx`:

```tsx
import { useCallback, type MouseEvent } from 'react';
import { css } from 'styled-system/css';
import { FitText } from '@/ui/components/grid/FitText';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const banner = css({
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  paddingInline: '10px',
  paddingBlock: '8px',
  minHeight: '44px',
  bg: 'surfaceElevated',
  borderRadius: '10px',
  border: '1px solid token(colors.border)',
});

const posChip = css({
  flexShrink: 0,
  paddingInline: '8px',
  height: '22px',
  display: 'inline-flex',
  alignItems: 'center',
  borderRadius: '999px',
  bg: 'color-mix(in srgb, token(colors.secondary.400) 18%, transparent)',
  color: 'secondary.400',
  fontSize: '11px',
  fontWeight: 'medium',
});

const clueTextWrap = css({
  flex: 1,
  minWidth: 0,
  fontSize: '13px',
  color: 'fg',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
});

const lenPill = css({
  flexShrink: 0,
  paddingInline: '6px',
  height: '20px',
  display: 'inline-flex',
  alignItems: 'center',
  borderRadius: '4px',
  bg: 'transparent',
  border: '1px solid token(colors.border)',
  color: 'fgMuted',
  fontSize: '11px',
  fontFamily: 'body',
});

const altChip = css({
  flexShrink: 0,
  paddingInline: '8px',
  height: '24px',
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  borderRadius: '999px',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  color: 'fgMuted',
  fontSize: '11px',
  cursor: 'pointer',
  touchAction: 'manipulation',
  _active: { transform: 'scale(0.97)' },
});

const empty = css({
  flex: 1,
  fontSize: '13px',
  color: 'fgMuted',
  fontStyle: 'italic',
});

const dirLabel = (dir: 'across' | 'down') => (dir === 'across' ? 'horiz.' : 'vert.');

export interface ClueBannerProps {
  readonly clue: Clue | null;
  readonly alternateClue: Clue | null;
  readonly onToggleDirection: () => void;
}

export function ClueBanner({ clue, alternateClue, onToggleDirection }: ClueBannerProps) {
  const handleAltMouseDown = useCallback((e: MouseEvent) => e.preventDefault(), []);
  if (!clue) {
    return (
      <div className={banner} aria-live="off">
        <span className={empty}>Touchez une case pour commencer</span>
      </div>
    );
  }
  return (
    <div className={banner}>
      <span className={posChip}>{dirLabel(clue.direction)}</span>
      <span className={clueTextWrap}>
        <FitText text={clue.clue.text} min={0.16} max={0.22} unit="ratio" />
      </span>
      <span className={lenPill}>{clue.cells.length}</span>
      {alternateClue ? (
        <button
          type="button"
          className={altChip}
          aria-label={`Basculer sur la définition ${dirLabel(alternateClue.direction)}`}
          onMouseDown={handleAltMouseDown}
          onClick={onToggleDirection}
        >
          ⇄ {dirLabel(alternateClue.direction)}
        </button>
      ) : null}
    </div>
  );
}
```

(`FitText` signature verified against `frontend/src/ui/components/grid/FitText.tsx:70-79`: `{ text, min, max, className?, title?, unit?: 'px' | 'ratio' }`. The ratio range `[0.16, 0.22]` matches the existing per-cell SINGLE/STACK band used elsewhere in `Cell.tsx`.)

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- clue-banner
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/ClueBanner.tsx frontend/tests/clue-banner.test.tsx
git commit -s -m "feat(frontend-keyboard): add ClueBanner with empty + alt-chip states"
```

### Task 2: Build `ActionRow` (Préc · Indice · Suiv)

**Files:**
- Create: `frontend/src/ui/components/keyboard/ActionRow.tsx`
- Test: `frontend/tests/keyboard-action-row.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ActionRow } from '@/ui/components/keyboard/ActionRow';

const baseProps = {
  onPrev: () => undefined,
  onNext: () => undefined,
  onHint: () => undefined,
  hintRemaining: 3,
  hintAllowed: 3,
  hintDisabled: false,
};

describe('ActionRow', () => {
  it('renders Préc, Indice (with counter), Suiv', () => {
    const { getByLabelText, getByText } = render(<ActionRow {...baseProps} />);
    expect(getByLabelText('Indice précédent')).toBeTruthy();
    expect(getByLabelText('Indice suivant')).toBeTruthy();
    expect(getByText(/Indice/)).toBeTruthy();
    expect(getByText(/3.*\/.*3/)).toBeTruthy();
  });

  it('clicking calls the correct callbacks', () => {
    const onPrev = vi.fn(), onNext = vi.fn(), onHint = vi.fn();
    const { getByLabelText } = render(<ActionRow {...baseProps} onPrev={onPrev} onNext={onNext} onHint={onHint} />);
    fireEvent.click(getByLabelText('Indice précédent'));
    fireEvent.click(getByLabelText('Indice suivant'));
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onPrev).toHaveBeenCalled();
    expect(onNext).toHaveBeenCalled();
    expect(onHint).toHaveBeenCalled();
  });

  it('hintDisabled=true blocks the hint button onPress', () => {
    const onHint = vi.fn();
    const { getByLabelText } = render(<ActionRow {...baseProps} onHint={onHint} hintDisabled />);
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onHint).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- keyboard-action-row
```

Expected: FAIL.

- [ ] **Step 3: Implement `ActionRow`**

Create `frontend/src/ui/components/keyboard/ActionRow.tsx`:

```tsx
import { css } from 'styled-system/css';
import { KeyboardKey } from './KeyboardKey';

const row = css({
  display: 'flex',
  gap: '4px',
  paddingBottom: '4px',
  borderBottom: '1px dashed token(colors.border)',
  marginBottom: '4px',
});

const hintLabel = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  fontSize: '12px',
  fontWeight: 'medium',
});

export interface ActionRowProps {
  readonly onPrev: () => void;
  readonly onNext: () => void;
  readonly onHint: () => void;
  readonly hintRemaining: number;
  readonly hintAllowed: number;
  readonly hintDisabled: boolean;
}

export function ActionRow({ onPrev, onNext, onHint, hintRemaining, hintAllowed, hintDisabled }: ActionRowProps) {
  return (
    <div className={row}>
      <KeyboardKey label="◀ Préc." ariaLabel="Indice précédent" variant="action" onPress={onPrev} />
      <KeyboardKey
        label={<span className={hintLabel}>💡 Indice {hintRemaining} / {hintAllowed}</span>}
        ariaLabel={`Demander un indice, ${hintRemaining} restants`}
        variant="action"
        disabled={hintDisabled}
        onPress={onHint}
      />
      <KeyboardKey label="Suiv. ▶" ariaLabel="Indice suivant" variant="action" onPress={onNext} />
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- keyboard-action-row
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/ActionRow.tsx frontend/tests/keyboard-action-row.test.tsx
git commit -s -m "feat(frontend-keyboard): add ActionRow with prev/hint/next"
```

### Task 3: Compose banner + action row + direction key into `MobileKeyboard`

**Files:**
- Modify: `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`
- Modify: `frontend/src/ui/components/keyboard/index.ts`
- Test: `frontend/tests/mobile-keyboard.test.tsx`

The panel grows: clue banner → action row → 3 letter rows → bottom row of letters + ⇄ + ⌫.

- [ ] **Step 1: Write a failing test**

Add to `frontend/tests/mobile-keyboard.test.tsx`:

```tsx
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const stubClue = (text: string, len: number): Clue => ({
  definition: { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text, arrow: 'right' }] },
  clue: { text, arrow: 'right' },
  direction: 'across',
  cells: Array.from({ length: len }, (_, i) => ({ kind: 'letter', position: { row: 0, col: i + 1 }, entry: '' })),
});

describe('MobileKeyboard banner + action row + direction', () => {
  const fullProps = {
    onLetter: () => undefined,
    onBackspace: () => undefined,
    onToggleDirection: () => undefined,
    onPrevClue: () => undefined,
    onNextClue: () => undefined,
    onRequestHint: () => undefined,
    activeClue: stubClue('Fruit', 5),
    alternateClue: null,
    hintRemaining: 3,
    hintAllowed: 3,
    hintExhausted: false,
    hintPending: false,
    focusedCellLocked: false,
  };

  it('shows the clue banner with active clue text', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByText('Fruit')).toBeTruthy();
  });

  it('renders the direction key in the bottom row', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByLabelText('Changer de sens')).toBeTruthy();
  });

  it('clicking the direction key calls onToggleDirection', () => {
    const onToggleDirection = vi.fn();
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} onToggleDirection={onToggleDirection} />);
    fireEvent.click(getByLabelText('Changer de sens'));
    expect(onToggleDirection).toHaveBeenCalled();
  });

  it('clicking the hint button calls onRequestHint', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} onRequestHint={onRequestHint} />);
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).toHaveBeenCalled();
  });

  it('clicking Suiv. ▶ calls onNextClue', () => {
    const onNextClue = vi.fn();
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} onNextClue={onNextClue} />);
    fireEvent.click(getByLabelText('Indice suivant'));
    expect(onNextClue).toHaveBeenCalled();
  });

  it('hint button disabled when exhausted, pending, locked cell, or no active clue', () => {
    for (const overrides of [
      { hintExhausted: true },
      { hintPending: true },
      { focusedCellLocked: true },
      { activeClue: null as unknown as Clue },
    ]) {
      const { getByLabelText, unmount } = render(<MobileKeyboard {...fullProps} {...overrides} />);
      const btn = getByLabelText(/Demander un indice/);
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      unmount();
    }
  });

  it('shows empty-state hint when activeClue is null', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} activeClue={null} />);
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: FAIL (props don't exist on `MobileKeyboardProps`).

- [ ] **Step 3: Expand `MobileKeyboard`**

In `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`, replace the existing implementation with:

```tsx
import { useEffect, useRef } from 'react';
import { css } from 'styled-system/css';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { AZERTY_ROWS } from './azertyLayout';
import { ClueBanner } from './ClueBanner';
import { ActionRow } from './ActionRow';
import { KeyboardKey } from './KeyboardKey';

const panel = css({
  position: 'fixed',
  insetInline: 0,
  insetBlockEnd: 0,
  bg: 'surface',
  borderTop: '1px solid token(colors.border)',
  paddingInline: '6px',
  paddingBlockStart: '8px',
  paddingBlockEnd: 'calc(env(safe-area-inset-bottom) + 8px)',
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  zIndex: 20,
});

const row = css({ display: 'flex', gap: '4px', justifyContent: 'center' });

export interface MobileKeyboardProps {
  readonly onLetter: (char: string) => void;
  readonly onBackspace: () => void;
  readonly onToggleDirection: () => void;
  readonly onPrevClue: () => void;
  readonly onNextClue: () => void;
  readonly onRequestHint: () => void;
  readonly activeClue: Clue | null;
  readonly alternateClue: Clue | null;
  readonly hintRemaining: number;
  readonly hintAllowed: number;
  readonly hintExhausted: boolean;
  readonly hintPending: boolean;
  readonly focusedCellLocked: boolean;
}

export function MobileKeyboard(props: MobileKeyboardProps) {
  const {
    onLetter, onBackspace, onToggleDirection, onPrevClue, onNextClue, onRequestHint,
    activeClue, alternateClue, hintRemaining, hintAllowed, hintExhausted, hintPending, focusedCellLocked,
  } = props;

  const panelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = panelRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => {
      document.documentElement.style.setProperty('--mobile-kb-height', `${Math.ceil(el.getBoundingClientRect().height)}px`);
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      document.documentElement.style.removeProperty('--mobile-kb-height');
    };
  }, []);

  const hintDisabled = hintExhausted || hintPending || focusedCellLocked || activeClue === null;
  const lettersInert = activeClue === null;

  return (
    <div ref={panelRef} className={panel} role="group" aria-label="Clavier mots fléchés">
      <ClueBanner clue={activeClue} alternateClue={alternateClue} onToggleDirection={onToggleDirection} />
      <ActionRow
        onPrev={onPrevClue}
        onNext={onNextClue}
        onHint={onRequestHint}
        hintRemaining={hintRemaining}
        hintAllowed={hintAllowed}
        hintDisabled={hintDisabled}
      />
      {AZERTY_ROWS.map((letters, rowIdx) => (
        <div key={rowIdx} className={row}>
          {letters.map((ch) => (
            <KeyboardKey
              key={ch}
              label={ch}
              ariaLabel={`Lettre ${ch}`}
              disabled={lettersInert}
              onPress={() => onLetter(ch)}
            />
          ))}
          {rowIdx === AZERTY_ROWS.length - 1 ? (
            <>
              <KeyboardKey label="⇄" ariaLabel="Changer de sens" variant="action" onPress={onToggleDirection} />
              <KeyboardKey label="⌫" ariaLabel="Effacer" variant="action" onPress={onBackspace} />
            </>
          ) : null}
        </div>
      ))}
    </div>
  );
}
```

Update `frontend/src/ui/components/keyboard/index.ts` to also export `ClueBanner` and `ActionRow`:

```ts
export { MobileKeyboard, type MobileKeyboardProps } from './MobileKeyboard';
export { ClueBanner, type ClueBannerProps } from './ClueBanner';
export { ActionRow, type ActionRowProps } from './ActionRow';
export { KeyboardKey, type KeyboardKeyProps } from './KeyboardKey';
export { useTouchPrimary } from './useTouchPrimary';
export { AZERTY_ROWS } from './azertyLayout';
```

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- mobile-keyboard
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/keyboard/MobileKeyboard.tsx frontend/src/ui/components/keyboard/index.ts frontend/tests/mobile-keyboard.test.tsx
git commit -s -m "feat(frontend-keyboard): compose banner + actions + direction"
```

### Task 4: Wire the new `MobileKeyboard` props from `Grid.tsx`

**Files:**
- Modify: `frontend/src/ui/components/keyboard/MobileKeyboard.tsx` (replace reactive `focusedCellLocked` with an imperative `getFocusedCell` callback)
- Modify: `frontend/src/ui/components/grid/Grid.tsx`
- Test: `frontend/tests/grid-input.test.tsx`, `frontend/tests/mobile-keyboard.test.tsx`

**Pattern note.** Per ADR-0002 §4 and the existing `HintControl` convention (`HintControl.tsx:7,113-115,127-131`), focused-cell state is read **imperatively at click time** via a callback, not threaded as a reactive prop. This avoids per-focus re-renders of the panel. So `MobileKeyboard` accepts a `getFocusedCell: () => FocusedCell | null` callback (same shape `HintControl` already uses) instead of a `focusedCellLocked` boolean.

- [ ] **Step 1: Replace `focusedCellLocked` with `getFocusedCell` on `MobileKeyboardProps`**

In `frontend/src/ui/components/keyboard/MobileKeyboard.tsx`, import the existing type and update the prop:

```tsx
import type { FocusedCell } from '@/ui/components/grid/HintControl';
```

In `MobileKeyboardProps`, remove `focusedCellLocked` and add:

```ts
readonly getFocusedCell: () => FocusedCell | null;
```

Where the hint button's `disabled` was computed using `focusedCellLocked || activeClue === null`, drop the `focusedCellLocked` term (it's no longer reactive) and rely on the click-time guard instead:

```tsx
const hintDisabled = hintExhausted || hintPending || activeClue === null;
```

Wrap `onRequestHint` to enforce the locked / no-focus guard at click time:

```tsx
const handleRequestHint = () => {
  const cell = getFocusedCell();
  if (!cell || cell.isLocked) return;
  onRequestHint();
};
```

Pass `handleRequestHint` (not the raw `onRequestHint`) to `ActionRow`.

Update the corresponding test in `mobile-keyboard.test.tsx` Task 3 Step 1: the case `{ focusedCellLocked: true }` becomes `{ getFocusedCell: () => ({ row: 0, column: 0, isLocked: true }) }`, and the assertion is "click does NOT invoke `onRequestHint`" (rather than "aria-disabled is true"). Adjust accordingly:

```tsx
it('hint click is a no-op when getFocusedCell returns a locked cell', () => {
  const onRequestHint = vi.fn();
  const { getByLabelText } = render(
    <MobileKeyboard
      {...fullProps}
      getFocusedCell={() => ({ row: 0, column: 0, isLocked: true })}
      onRequestHint={onRequestHint}
    />,
  );
  fireEvent.click(getByLabelText(/Demander un indice/));
  expect(onRequestHint).not.toHaveBeenCalled();
});

it('hint click is a no-op when getFocusedCell returns null', () => {
  const onRequestHint = vi.fn();
  const { getByLabelText } = render(
    <MobileKeyboard
      {...fullProps}
      getFocusedCell={() => null}
      onRequestHint={onRequestHint}
    />,
  );
  fireEvent.click(getByLabelText(/Demander un indice/));
  expect(onRequestHint).not.toHaveBeenCalled();
});
```

The fixture `fullProps` gets `getFocusedCell: () => ({ row: 0, column: 0, isLocked: false })` instead of `focusedCellLocked: false`.

- [ ] **Step 2: Write a failing test for `Grid` forwarding**

Append to `frontend/tests/grid-input.test.tsx`:

```tsx
it('Grid forwards hint props into MobileKeyboard when touch-primary', () => {
  const original = window.matchMedia;
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList);
  try {
    const onRequestHint = vi.fn();
    const getFocusedCell = vi.fn().mockReturnValue({ row: 1, column: 1, isLocked: false });
    const { container, getByLabelText } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        hintRemaining={2}
        hintAllowed={3}
        hintExhausted={false}
        hintPending={false}
        onRequestHint={onRequestHint}
        getFocusedCell={getFocusedCell}
      />,
    );
    click(inputAt(container, 1, 1)!);
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).toHaveBeenCalled();
  } finally {
    window.matchMedia = original;
  }
});
```

- [ ] **Step 3: Run to verify failure**

```sh
cd frontend && pnpm test -- grid-input mobile-keyboard
```

Expected: FAIL — the new `Grid` props don't exist.

- [ ] **Step 4: Add the props to `Grid` and forward**

In `frontend/src/ui/components/grid/Grid.tsx`, extend the props interface:

```ts
import type { FocusedCell } from './HintControl';

export interface GridProps {
  // ... existing props ...
  readonly hintRemaining?: number;
  readonly hintAllowed?: number;
  readonly hintExhausted?: boolean;
  readonly hintPending?: boolean;
  readonly onRequestHint?: (row: number, column: number) => void;
  readonly getFocusedCell?: () => FocusedCell | null;
}
```

Destructure with defaults:

```tsx
const {
  /* existing */,
  hintRemaining = 0,
  hintAllowed = 0,
  hintExhausted = true,
  hintPending = false,
  onRequestHint,
  getFocusedCell,
} = props;
```

Replace the Phase-3 `MobileKeyboard` render with:

```tsx
{touchPrimary ? (
  <MobileKeyboard
    onLetter={(ch) => nav.enterLetter(ch)}
    onBackspace={() => nav.eraseLetter()}
    onToggleDirection={() => nav.toggleDirection()}
    onPrevClue={() => nav.cycleClue(-1)}
    onNextClue={() => nav.cycleClue(1)}
    onRequestHint={() => {
      const cell = (getFocusedCell?.() ?? null);
      if (!cell || !onRequestHint) return;
      onRequestHint(cell.row, cell.column);
    }}
    getFocusedCell={getFocusedCell ?? (() => null)}
    activeClue={nav.currentClue}
    alternateClue={nav.alternateClue}
    hintRemaining={hintRemaining}
    hintAllowed={hintAllowed}
    hintExhausted={hintExhausted || onRequestHint === undefined}
    hintPending={hintPending}
  />
) : null}
```

- [ ] **Step 5: Run tests to verify pass**

```sh
cd frontend && pnpm test -- grid-input mobile-keyboard
```

Expected: PASS.

- [ ] **Step 6: Commit**

```sh
git add frontend/src/ui/components/grid/Grid.tsx frontend/src/ui/components/keyboard/MobileKeyboard.tsx frontend/tests/grid-input.test.tsx frontend/tests/mobile-keyboard.test.tsx
git commit -s -m "feat(frontend-grid): thread hint state and focused-cell callback into MobileKeyboard"
```

### Task 5: Pass hint state from `grille.tsx` route into `Grid`

**Files:**
- Modify: `frontend/src/ui/routes/grille.tsx`
- Modify: `frontend/src/ui/routes/lobby.$lobbyId.lazy.tsx` (if it owns its own `Grid`)

Currently the route owns `useHintRequest()` and passes its state into `HintControl` via `hintSlot`. The same state must reach `Grid` too.

- [ ] **Step 1: Find the existing `useHintRequest` usage in `grille.tsx`**

Search for `useHintRequest(` in `frontend/src/ui/routes/grille.tsx`. Identify the destructured state.

- [ ] **Step 2: Thread state into `<Grid>`**

In the same render where `<Grid puzzle={...} ... />` is invoked, pass the additional props:

```tsx
<Grid
  puzzle={puzzle}
  // ... existing props ...
  hintRemaining={hintRequest.hintsRemaining}
  hintAllowed={puzzle.hintsAllowed}
  hintExhausted={hintRequest.exhausted}
  hintPending={hintRequest.pending}
  onRequestHint={hintRequest.request}
/>
```

(Names follow whatever the local variable is called — adjust to match the route's existing identifier for the `useHintRequest` return value.)

For multiplayer (`lobby.$lobbyId.lazy.tsx`): the route does NOT call `useHintRequest` (hints are solo-only per ADR-0018). Leave the new `Grid` props undefined — the Phase 4 Task 4 defaults make the hint button effectively disabled on touch-primary multiplayer, which is the desired behavior (no hints in multiplayer).

- [ ] **Step 3: Run all frontend tests**

```sh
cd frontend && pnpm test
```

Expected: all green. (Route tests don't directly exercise hint threading; they'll just keep passing.)

- [ ] **Step 4: Manual smoke check**

```sh
cd frontend && pnpm dev
```

Open Chrome devtools, switch to iPhone 13 preset, navigate to `/grille`. Verify: panel mounts, banner shows current clue, hint counter visible, prev/next/direction tap actions work. Tap the hint pill — letter reveals; counter decrements.

(Skip if devtools mobile emulation is unavailable; the e2e suite in Phase 6 covers this with a real headless browser.)

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/routes/grille.tsx
git commit -s -m "feat(frontend-route-grille): thread hint state into Grid for mobile keyboard"
```

### Task 6: Open Phase 4 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin feat/frontend-mobile-keyboard-banner-actions
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "feat(frontend-keyboard): clue banner + prev/hint/next + direction key" --body "$(cat <<'EOF'
## Summary
- `ClueBanner` (active clue text + length pill + alt-clue chip + empty state) and `ActionRow` (prev / hint / next) compose into `MobileKeyboard`.
- Bottom row gains a `⇄` direction-toggle key next to backspace.
- Hint state threaded from `grille.tsx` → `Grid` → `MobileKeyboard`. Multiplayer route leaves hint props undefined (hints are solo-only per ADR-0018); the hint button stays disabled there.
- Phase 4 of the mobile custom keyboard rollout.

## Test plan
- [ ] `pnpm test -- mobile-keyboard clue-banner keyboard-action-row grid-input` green.
- [ ] `pnpm typecheck` green.
- [ ] Manual (mobile emulation): clue banner shows, hint pill works with counter, prev/next cycles clues, direction key flips at intersection.
EOF
)"
```

---

## Phase 5: Mobile-only removal of `CurrentCluePanel` + toolbar `HintControl`

**Goal:** On touch-primary devices, the existing `CurrentCluePanel` (top sticky banner) and the toolbar's `HintControl` pill don't render — the new panel owns those affordances. Desktop unchanged.

**Branch:** `feat/frontend-mobile-keyboard-dedupe-ui`

### Task 1: Skip `CurrentCluePanel` render in `Grid.tsx` on touch-primary

**Files:**
- Modify: `frontend/src/ui/components/grid/Grid.tsx` (the `<CurrentCluePanel ...>` site near line 950)
- Test: `frontend/tests/grid-input.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
it('CurrentCluePanel is not rendered on touch-primary; rendered on desktop', () => {
  const original = window.matchMedia;
  const setMatches = (v: boolean) => {
    window.matchMedia = vi.fn().mockReturnValue({
      matches: v,
      media: '(any-pointer: coarse) and (any-hover: none)',
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => true,
    } as MediaQueryList);
  };
  try {
    setMatches(true);
    const r1 = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(r1.queryByTestId('current-clue-panel')).toBeNull();
    r1.unmount();
    setMatches(false);
    const r2 = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(r2.queryByTestId('current-clue-panel')).toBeTruthy();
  } finally {
    window.matchMedia = original;
  }
});
```

- [ ] **Step 2: Run to verify failure**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: FAIL — `current-clue-panel` testid found on both renders.

- [ ] **Step 3: Gate the render**

In `frontend/src/ui/components/grid/Grid.tsx`, find the `<CurrentCluePanel ...>` render block (around line 950). Wrap it:

```tsx
{!touchPrimary ? (
  <CurrentCluePanel
    clue={nav.currentClue}
    cellIndex={nav.currentClueIndex}
    alternateClue={nav.alternateClue}
    onSwitchDirection={nav.toggleDirection}
    getEntryAt={nav.getEntryAt}
  />
) : null}
```

(Use whatever props the existing render passes — don't reshape; just wrap.)

- [ ] **Step 4: Run tests to verify pass**

```sh
cd frontend && pnpm test -- grid-input
```

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/components/grid/Grid.tsx frontend/tests/grid-input.test.tsx
git commit -s -m "feat(frontend-grid): hide CurrentCluePanel on touch-primary"
```

### Task 2: Skip toolbar `HintControl` slot in `grille.tsx` on touch-primary

**Files:**
- Modify: `frontend/src/ui/routes/grille.tsx`
- Test: `frontend/tests/grille-route-toolbar.test.tsx` (new — small route-level test)

**Testing note.** MANIFESTO "No mocks of what you own": `useTouchPrimary` is our hook, so we don't mock it. The real external boundary is `window.matchMedia` — stub that instead, exactly like Phase 2 Task 1 already does.

- [ ] **Step 1: Write a failing test using `window.matchMedia` at the boundary**

Create `frontend/tests/grille-route-toolbar.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { PuzzleToolbar } from '@/ui/components/layout/PuzzleToolbar';
import { HintControl } from '@/ui/components/grid/HintControl';
import { useTouchPrimary } from '@/ui/components/keyboard';

function setMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockReturnValue({
    matches,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList);
}

function Harness() {
  const touchPrimary = useTouchPrimary();
  return (
    <PuzzleToolbar
      metadata="Test"
      hintSlot={
        touchPrimary ? undefined : (
          <HintControl
            hintsRemaining={3}
            hintsAllowed={3}
            exhausted={false}
            pending={false}
            lastResult={null}
            errorMessage={null}
            getFocusedCell={() => null}
            onRequest={() => undefined}
          />
        )
      }
    />
  );
}

describe('toolbar hint gating by touch-primary', () => {
  const original = window.matchMedia;
  afterEach(() => { window.matchMedia = original; });

  it('renders HintControl on desktop', () => {
    setMatchMedia(false);
    const { queryByRole } = render(<Harness />);
    expect(queryByRole('button', { name: /Indice/ })).toBeTruthy();
  });

  it('omits HintControl on touch-primary', () => {
    setMatchMedia(true);
    const { queryByRole } = render(<Harness />);
    expect(queryByRole('button', { name: /Indice/ })).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it passes**

```sh
cd frontend && pnpm test -- grille-route-toolbar
```

Expected: PASS — the harness encodes the production gating logic exactly. (No red-then-green here; the gating logic is structural enough that the test is a documentation-by-example, not a regression for fresh code.)

- [ ] **Step 3: Apply the gating in the route**

In `frontend/src/ui/routes/grille.tsx`, near the existing imports:

```tsx
import { useTouchPrimary } from '@/ui/components/keyboard';
```

Inside the route component:

```tsx
const touchPrimary = useTouchPrimary();
```

Find the `<PuzzleToolbar ... hintSlot={<HintControl ... />} ... />` call. Replace `hintSlot` with:

```tsx
hintSlot={touchPrimary ? undefined : (
  <HintControl
    // ... existing props ...
  />
)}
```

- [ ] **Step 4: Run all frontend tests**

```sh
cd frontend && pnpm test
```

Expected: all green.

- [ ] **Step 5: Commit**

```sh
git add frontend/src/ui/routes/grille.tsx frontend/tests/grille-route-toolbar.test.tsx
git commit -s -m "feat(frontend-route-grille): hide toolbar HintControl on touch-primary"
```

### Task 3: Open Phase 5 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin feat/frontend-mobile-keyboard-dedupe-ui
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "feat(frontend-keyboard): hide CurrentCluePanel + toolbar HintControl on touch-primary" --body "$(cat <<'EOF'
## Summary
- On touch-primary devices, `CurrentCluePanel` and the toolbar `HintControl` are no longer rendered. The `MobileKeyboard` panel owns the clue display and hint UI.
- Desktop layout untouched.
- Phase 5 of the mobile custom keyboard rollout.

## Test plan
- [ ] `pnpm test` green.
- [ ] `pnpm typecheck` green.
- [ ] Manual (mobile emulation): top sticky clue panel and toolbar hint pill absent; keyboard's banner + hint pill present.
- [ ] Manual (desktop): both top panel and toolbar hint pill present; no keyboard panel.
EOF
)"
```

---

## Phase 6: A11y polish + Playwright suite + manual device pass

**Goal:** Confirm WCAG 2.5.5 AA tap targets at every supported viewport, exercise the full mobile flow under real browser emulation, and run axe-core to catch any a11y regression.

**Branch:** `test/frontend-mobile-keyboard-a11y-e2e`

### Task 1: Playwright spec — happy path on iPhone 13

**Files:**
- Create: `frontend/e2e/mobile-keyboard.spec.ts`

- [ ] **Step 1: Write the spec**

Create `frontend/e2e/mobile-keyboard.spec.ts`:

```ts
import { test, expect, devices } from '@playwright/test';

test.use({ ...devices['iPhone 13'] });

test.describe('mobile custom keyboard', () => {
  test('panel renders on touch-primary and CurrentCluePanel does not', async ({ page }) => {
    await page.goto('/grille');
    await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();
    await expect(page.getByTestId('current-clue-panel')).toHaveCount(0);
  });

  test('tap a cell, type letters, see them in the grid', async ({ page }) => {
    await page.goto('/grille');
    // First letter cell — selector matches data attributes set by Cell.tsx
    const firstCell = page.locator('[data-cell-kind="letter"]').first();
    await firstCell.click();
    await page.getByLabel('Lettre B').click();
    await page.getByLabel('Lettre O').click();
    await page.getByLabel('Lettre N').click();
    await expect(firstCell).toHaveValue('B');
  });

  test('backspace clears the last letter', async ({ page }) => {
    await page.goto('/grille');
    const firstCell = page.locator('[data-cell-kind="letter"]').first();
    await firstCell.click();
    await page.getByLabel('Lettre A').click();
    await page.getByLabel('Effacer').click();
    await expect(firstCell).toHaveValue('A');
    await page.getByLabel('Effacer').click();
    await expect(firstCell).toHaveValue('');
  });

  test('Suiv. ▶ moves to the next clue', async ({ page }) => {
    await page.goto('/grille');
    await page.locator('[data-cell-kind="letter"]').first().click();
    const cluetextBefore = await page.locator('[role="group"][aria-label="Clavier mots fléchés"]').textContent();
    await page.getByLabel('Indice suivant').click();
    const cluetextAfter = await page.locator('[role="group"][aria-label="Clavier mots fléchés"]').textContent();
    expect(cluetextAfter).not.toBe(cluetextBefore);
  });

  test('Indice reveals a letter and decrements the counter', async ({ page }) => {
    await page.goto('/grille');
    await page.locator('[data-cell-kind="letter"]').first().click();
    const counterBefore = await page.getByLabel(/Demander un indice/).getAttribute('aria-label');
    await page.getByLabel(/Demander un indice/).click();
    await expect(page.getByLabel(/Demander un indice/)).not.toHaveAttribute('aria-label', counterBefore!);
  });
});

test.describe('desktop (no touch-primary)', () => {
  test.use({ ...devices['Desktop Chrome'] });
  test('panel absent; CurrentCluePanel present', async ({ page }) => {
    await page.goto('/grille');
    await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toHaveCount(0);
    await expect(page.getByTestId('current-clue-panel')).toBeVisible();
  });
});
```

- [ ] **Step 2: Run the spec headlessly**

```sh
cd frontend && pnpm e2e mobile-keyboard
```

Expected: all green. If any test fails, the gap is likely a selector mismatch — inspect the failing locator output and adjust either the test or the matching `aria-label`. Do **not** weaken assertions to make a test pass; fix the underlying mismatch.

- [ ] **Step 3: Commit**

```sh
git add frontend/e2e/mobile-keyboard.spec.ts
git commit -s -m "test(frontend-keyboard): Playwright suite for mobile keyboard happy path"
```

### Task 2: Tap-target audit at the narrowest supported viewport

**Files:**
- Create: `frontend/e2e/mobile-keyboard-tap-targets.spec.ts`

The spec calls for ≥44×44 CSS-px hit areas on every button. WCAG 2.5.5 measures hit area, not visible area, so the audit reads each button's computed `getBoundingClientRect`.

- [ ] **Step 1: Write the spec**

Create `frontend/e2e/mobile-keyboard-tap-targets.spec.ts`:

```ts
import { test, expect } from '@playwright/test';

test.use({
  viewport: { width: 320, height: 568 }, // iPhone SE 1st gen
  hasTouch: true,
  isMobile: true,
});

test('every keyboard button has a tap target of at least 44x44', async ({ page }) => {
  await page.goto('/grille');
  const panel = page.getByRole('group', { name: 'Clavier mots fléchés' });
  await expect(panel).toBeVisible();
  const buttons = await panel.getByRole('button').all();
  expect(buttons.length).toBeGreaterThan(20);
  for (const btn of buttons) {
    const box = await btn.boundingBox();
    expect(box).not.toBeNull();
    // Allow a 0.5 px subpixel tolerance.
    expect(box!.width).toBeGreaterThanOrEqual(43.5);
    expect(box!.height).toBeGreaterThanOrEqual(43.5);
  }
});
```

- [ ] **Step 2: Run the spec**

```sh
cd frontend && pnpm e2e mobile-keyboard-tap-targets
```

Expected: PASS. If FAIL — the `KeyboardKey` padding needs raising. Adjust `paddingInline` / `paddingBlock` on `keyBase` in `KeyboardKey.tsx` until every button is ≥44×44 at 320×568 viewport, then re-run.

- [ ] **Step 3: Commit**

```sh
git add frontend/e2e/mobile-keyboard-tap-targets.spec.ts
# If KeyboardKey.tsx changed:
git add frontend/src/ui/components/keyboard/KeyboardKey.tsx
git commit -s -m "test(frontend-keyboard): tap-target audit at 320px viewport"
```

### Task 3: axe-core run over the panel

**Files:**
- Create: `frontend/e2e/mobile-keyboard-a11y.spec.ts`

- [ ] **Step 1: Write the spec**

Create `frontend/e2e/mobile-keyboard-a11y.spec.ts`:

```ts
import { test, expect, devices } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test.use({ ...devices['iPhone 13'] });

test('mobile keyboard has no axe-core violations', async ({ page }) => {
  await page.goto('/grille');
  await page.locator('[data-cell-kind="letter"]').first().click();
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();
  const results = await new AxeBuilder({ page })
    .include('[role="group"][aria-label="Clavier mots fléchés"]')
    .analyze();
  expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
});
```

- [ ] **Step 2: Run the spec**

```sh
cd frontend && pnpm e2e mobile-keyboard-a11y
```

Expected: PASS. If any violation surfaces — read the message, fix at the source (most commonly a missing `aria-label` or insufficient color contrast). Re-run.

- [ ] **Step 3: Commit**

```sh
git add frontend/e2e/mobile-keyboard-a11y.spec.ts
# Plus any source fixes that fell out of the axe report
git commit -s -m "test(frontend-keyboard): axe-core a11y audit for mobile panel"
```

### Task 4: Manual device test pass + recordings

**Goal:** Verify on real hardware that the OS keyboard does not pop, the panel sits above the safe area, and `matchMedia` correctly excludes iPad+Magic-Keyboard. This is documented in the PR body; not automated.

- [ ] **Step 1: iOS Safari (real iPhone)**

Navigate to a preview deploy of the branch. Tap a cell. **Expected:** no OS keyboard appears; the custom panel is at the bottom; the bottom row sits above the home indicator.

- [ ] **Step 2: Android Chrome (real Android phone)**

Same checks. Verify: no Gboard popup, no autofill suggestion bar on the input.

- [ ] **Step 3: iPad with Magic Keyboard attached**

Navigate to the preview. **Expected:** the custom panel does NOT render; cells use the physical keyboard (and the OS soft keyboard never opens on a keyboard-attached iPad anyway).

- [ ] **Step 4: iPad without keyboard**

**Expected:** custom panel renders; keys are appropriately sized.

- [ ] **Step 5: Record results in the PR body**

Add a `## Manual device test results` section to the Phase 6 PR body. One line per device per check, with pass/fail.

### Task 5: Open Phase 6 PR

- [ ] **Step 1: Push the branch**

```sh
git push -u origin test/frontend-mobile-keyboard-a11y-e2e
```

- [ ] **Step 2: Open the PR**

```sh
gh pr create --title "test(frontend-keyboard): a11y + e2e coverage for mobile panel" --body "$(cat <<'EOF'
## Summary
- Playwright happy-path suite (iPhone 13 emulation): cell focus, letter typing, backspace, clue navigation, hint reveal.
- Desktop (Desktop Chrome) negative test: panel absent, CurrentCluePanel present.
- Tap-target audit at 320×568 viewport (iPhone SE 1st gen): every button ≥ 44×44.
- axe-core a11y scan over the panel: zero violations.
- Phase 6 (final) of the mobile custom keyboard rollout. Spec: `docs/superpowers/specs/2026-05-21-mobile-custom-keyboard-design.md`.

## Manual device test results
[fill in from Task 4]

## Test plan
- [ ] `pnpm e2e mobile-keyboard` green.
- [ ] `pnpm e2e mobile-keyboard-tap-targets` green.
- [ ] `pnpm e2e mobile-keyboard-a11y` green.
- [ ] Manual device pass complete (see results above).
EOF
)"
```

---

## Self-review checklist (for the plan author)

Run through this before handoff:

- **Spec coverage.** Walk each spec section. Banner empty state → Phase 4 Task 1. AZERTY layout → Phase 3 Task 1. Touch-primary detection → Phase 2 Task 1. Command extraction → Phase 1. CurrentCluePanel removal → Phase 5 Task 1. HintControl removal → Phase 5 Task 2. WCAG tap targets → Phase 6 Task 2. Manual device pass → Phase 6 Task 4. Pinch-zoom: structurally solved by mounting the keyboard outside the `react-zoom-pan-pinch` `TransformWrapper`; no per-task work required.
- **Type consistency.** `enterLetter`, `eraseLetter`, `cycleClue` signatures are identical across phases. `MobileKeyboardProps` evolves additively (Phase 3 → Phase 4) without breaking earlier callers; `getFocusedCell` replaces `focusedCellLocked` in Phase 4 Task 4 with the change applied consistently in both component code and tests. `useTouchPrimary` returns `boolean` everywhere.
- **No placeholders.** Every test has real code. Every implementation has real code. No "implement appropriate X" or "handle edge cases" — each step shows what to write.
- **MANIFESTO alignment.**
  - **TDD red-green-refactor** ✓ except Phase 1 (pure refactor — existing suite IS the regression guard, no new tests needed) and Phase 5 Task 2 (gating logic is structural; the test is documentation-by-example).
  - **No mocks of what you own** ✓ — only `window.matchMedia` (browser boundary), `react-zoom-pan-pinch` (third-party), and `ResizeObserver`/`visualViewport` (browser APIs) are stubbed. `useTouchPrimary` is never mocked.
  - **Mock only at external boundaries** ✓.
  - **Accessibility first-class** ✓ — every button has aria-label, role=group has accessible name, hit targets ≥44×44, axe-core scan in Phase 6.
  - **Hexagonal boundaries respected** ✓ — no domain or application changes; pure UI; routes own `useHintRequest`.
  - **Small PRs (≤400 lines)** ✓ — Phase 4 is the tightest at an estimated ~350 lines; if it overflows during execution, split Tasks 1–3 (banner + action row) from Tasks 4–5 (wiring) into two PRs.
  - **Candor over compliance** ✓ — the "Plan divergences from spec" and "Pinch-zoom" sections name the design decisions explicitly rather than burying them.

If anything in the plan is uncertain at execution time, prefer to verify against the spec or the real source file rather than guessing. CLAUDE.md: "Verify, don't guess."
