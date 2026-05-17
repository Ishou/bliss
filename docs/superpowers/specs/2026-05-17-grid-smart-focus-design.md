# Grid Smart-Focus on Click

## Context

The grid's click-to-focus logic in `frontend/src/ui/components/grid/useGridNavigation.ts` (`handleClick`, ~lines 410–470) currently picks a solving direction with the following priority on a first click at cell `P`:

1. Clues that **structurally start** at `P` (the cell is the first letter of the clue) win over clues that merely pass through.
2. Among the surviving candidates: if exactly one, take it; else if the current solving direction matches one, keep it; else first by puzzle order (across before down).

That rule produces the "vertical wins" behavior when a cell is both a down-word start and the middle of an across word — which the maintainer agrees is the right default *when no progress has been made yet*.

The gap: it doesn't account for letters the player has already typed. In `H E . . .` (a 5-letter across word with the first two cells filled), clicking the third cell — the first `.` — should put the player in the across clue, because the filled prefix `HE` is a strong signal that's the word they're working on. Today's rule ignores entries and routes them into whatever the previous direction was, or the perpendicular real-start clue if one happens to exist.

## Decision

Extend `handleClick`'s first-click branch with a **smart-start** concept that classifies a clue at the clicked cell by whether its prefix is fully filled, and route smart-starts ahead of structural real-starts.

### The rule

For each clue `C` passing through clicked cell `P`, define:

- **smart-start(C, P)** ⇔ every letter cell of `C` strictly before `P` carries a non-empty value in `cellValuesRef`. The clicked cell's own value and any cells after it are irrelevant. When `P` is the first cell of `C`, the prefix is empty and the predicate is vacuously true — so every real-start is also a smart-start.
- **real-start(C, P)** ⇔ `P` is the first letter of `C` (existing notion).

Candidate selection at the clicked cell:

```
candidates = clues where smart-start(C, P)
           else clues where real-start(C, P)
           else all clues passing through P
```

Then apply the existing tiebreak unchanged: if exactly one candidate, take it; else if the current solving direction matches one of the candidates, keep it; else the first by `orderedClues` puzzle order (across before down).

Smart-start outranks real-start: when the user has clearly been making progress on one word, that signal wins over a structural word-boundary in the other direction.

### Worked examples

| Grid state at clicked cell                                                    | Today's behavior | New behavior |
|--------------------------------------------------------------------------------|------------------|--------------|
| Across `H E . . .`, click idx 2; no down clue                                  | Across (prev dir or fallback) | Across (smart-start) |
| Across all-empty `. . . . .`, click idx 2; idx 2 is also a down-start          | Down (real-start) | Down (no smart-start on either; real-start wins) |
| Across `H E . . .`, click idx 2; idx 2 is also a down-start                    | Down (real-start) | **Across (smart-start beats real-start)** |
| Across `H . . . .`, click idx 2 (partial prefix); idx 2 is a down-start        | Down (real-start) | Down (across smart-start fails; real-start wins) |
| Both across and across+down have full filled prefixes at clicked cell          | (varies)         | Existing tiebreak: current direction if it's a candidate, else across |
| Click the first cell of a word, empty prefix                                   | That word        | That word (real-start = smart-start when prefix is empty) |

The third row is the deliberate behavior change. The maintainer confirmed this is the intended trade.

### Scope

- Only the first-click branch of `handleClick` changes. Repeat-click (the toggle path), Tab/Enter cycling (`orderedClues`), Space (direction flip), and arrow keys are explicit user intents and remain untouched.
- No domain or schema changes.
- No new state. `cellValuesRef` is already maintained by the hook and is the single source of truth for typed letters.

## Architecture

Single-file change in `frontend/src/ui/components/grid/useGridNavigation.ts`. The smart-start check is a pure read on the existing `cellValuesRef.current` map. Inside `handleClick`:

```
const starting = allClues.filter((c) => same(c.cells[0].position, p));     // existing
const smart = allClues.filter((c) => prefixFilled(c, p, cellValuesRef));   // new
const candidates = smart.length > 0
  ? smart
  : starting.length > 0 ? starting : allClues;
// existing tiebreak applies to `candidates`
```

`prefixFilled` walks `clue.cells` from index 0 up to (but not including) the clicked cell's index; returns `true` iff every entry under those positions is non-empty in `cellValuesRef`. If the clicked cell isn't in `clue.cells` at all (defensive — `cluesAt(p)` should only return clues containing `p`), return `false`.

## Testing strategy

Add cases to `frontend/tests/grid-input.test.tsx` near the existing starting-clue tests (~line 248). The grid layout under test will need cells that expose each shape; reuse the existing harness pattern (`click(inputAt(container, r, c))`).

1. **Filled prefix, no real-start collision** — across word `H E . . .` with the second-half cells empty; type `H`, `E`, then click the cell at idx 2. Assert: current clue is across.
2. **Smart-start beats real-start** — cell is the start of a down word AND middle of across with full filled prefix. Assert: across wins. This is the new behavior — a dedicated test guards the deliberate change.
3. **Partial prefix does not trigger smart-start** — across word with only one of two preceding cells filled, clicked cell is a down-start. Assert: down wins (existing behavior preserved).
4. **Empty prefix does not trigger smart-start** — the historical "good case." Assert: down wins.
5. **Both directions have smart-start, current direction is one of them** — assert current direction sticks.
6. **Both directions have smart-start, current direction is neither** — assert across wins (puzzle-order tiebreak).
7. **Real-start with empty prefix still works** — regression guard.

Existing tests at lines 248–266 ("clicking the first cell of a word focuses that word, then toggles on repeat") and the repeat-click toggle test must continue to pass unchanged.

## Edge cases

- **Clicked cell is the word's first cell**: prefix is empty → smart-start vacuously true → identical to real-start. No special case required.
- **Clicked cell is filled itself**: only the prefix is inspected, so a filled clicked cell does not affect smart-start classification.
- **Repeat-click**: unchanged. Smart-start does not affect the toggle path.
- **No clues at the clicked cell**: unreachable in practice for letter cells; the existing fallback (`allClues` empty → leave direction as-is) still applies.

## Consequences

**Easier:** the "I just filled HE, now what's next?" tap routes the player into the across clue without an extra direction-toggle, which is the dominant flow on mobile where direction toggles are physically costly.

**Different:** in the specific shape "down-start cell sitting at the end of a fully filled across prefix," the click now lands on across instead of down. Maintainer has confirmed this is the intended trade.

**No harder:** no new state, no new render paths, no new wire format. The smart-start check is a small read-only walk against an existing ref.
